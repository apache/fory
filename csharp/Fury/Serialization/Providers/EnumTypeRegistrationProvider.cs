using System;
using System.Diagnostics.CodeAnalysis;
using System.Reflection;
using Fury.Context;

namespace Fury.Serialization;

internal static class EnumTypeRegistrationProvider
{
    private static MethodInfo CreateEnumSerializerMethod { get; } =
        typeof(EnumTypeRegistrationProvider).GetMethod(
            nameof(CreateEnumSerializer),
            BindingFlags.NonPublic | BindingFlags.Static
        )!;

    private static MethodInfo CreateEnumDeserializerMethod { get; } =
        typeof(EnumTypeRegistrationProvider).GetMethod(
            nameof(CreateEnumDeserializer),
            BindingFlags.NonPublic | BindingFlags.Static
        )!;

    private static ISerializer CreateEnumSerializer<TEnum>()
        where TEnum : unmanaged, Enum
    {
        return new EnumSerializer<TEnum>();
    }

    private static IDeserializer CreateEnumDeserializer<TEnum>()
        where TEnum : unmanaged, Enum
    {
        return new EnumDeserializer<TEnum>();
    }

    private static bool CanHandle(Type targetType)
    {
        return targetType.IsEnum;
    }

    public static bool TryRegisterType(
        TypeRegistry registry,
        Type targetType,
        [NotNullWhen(true)] out TypeRegistration? registration
    )
    {
        if (!CanHandle(targetType))
        {
            registration = null;
            return false;
        }
        var method = CreateEnumSerializerMethod.MakeGenericMethod(targetType);
        var serializerFactory = (Func<ISerializer>)method.CreateDelegate(typeof(Func<ISerializer>));
        method = CreateEnumDeserializerMethod.MakeGenericMethod(targetType);
        var deserializerFactory = (Func<IDeserializer>)method.CreateDelegate(typeof(Func<IDeserializer>));

        registration = registry.Register(targetType, serializerFactory, deserializerFactory);
        return true;
    }

    public static bool TryGetTypeRegistration(
        TypeRegistry registry,
        string? @namespace,
        string name,
        [NotNullWhen(true)] out TypeRegistration? registration
    )
    {
        // TODO: Implement by-name serialization for enums
        registration = null;
        return false;
    }
}
