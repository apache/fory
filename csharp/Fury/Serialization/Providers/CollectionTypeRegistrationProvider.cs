using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics.CodeAnalysis;
using System.Reflection;
using Fury.Context;
using Fury.Helpers;
using Fury.Meta;

namespace Fury.Serialization;

internal static class CollectionTypeRegistrationProvider
{
    private static readonly MethodInfo CreateListSerializerMethodInfo =
        typeof(CollectionTypeRegistrationProvider).GetMethod(
            nameof(CreateListSerializer),
            BindingFlags.NonPublic | BindingFlags.Static
        )!;

    private static readonly MethodInfo CreateListDeserializerMethodInfo =
        typeof(CollectionTypeRegistrationProvider).GetMethod(
            nameof(CreateListDeserializer),
            BindingFlags.NonPublic | BindingFlags.Static
        )!;

    /// <summary>
    /// <list type="bullet">
    /// <listheader>
    /// Supported types:
    /// </listheader>
    /// <item><description><see cref="List{T}"/></description></item>
    /// </list>
    /// </summary>
    public static bool TryRegisterType(
        TypeRegistry registry,
        Type targetType,
        [NotNullWhen(true)] out TypeRegistration? registration
    )
    {
        if (!targetType.IsGenericType)
        {
            registration = null;
            return false;
        }
        if (targetType.GetGenericTypeDefinition() != typeof(List<>))
        {
            registration = null;
            return false;
        }

        var elementType = targetType.GetGenericArguments()[0];
        var createSerializer = CreateListSerializerMethodInfo
            .MakeGenericMethod(elementType)
            .CreateDelegate<Func<TypeRegistration?, ISerializer>>();
        var createDeserializer = CreateListDeserializerMethodInfo
            .MakeGenericMethod(elementType)
            .CreateDelegate<Func<TypeRegistration?, IDeserializer>>();
        Func<ISerializer> serializerFactory;
        Func<IDeserializer> deserializerFactory;
        if (elementType.IsSealed)
        {
            var elementRegistration = registry.GetTypeRegistration(elementType);
            serializerFactory = () => createSerializer(elementRegistration);
            deserializerFactory = () => createDeserializer(elementRegistration);
        }
        else
        {
            serializerFactory = () => createSerializer(null);
            deserializerFactory = () => createDeserializer(null);
        }

        var typeKind = TypeKindHelper.SelectListTypeKind(elementType);
        registration = registry.Register(targetType, typeKind, serializerFactory, deserializerFactory);
        return true;
    }

    /// <summary>
    /// <list type="bullet">
    /// <listheader>
    /// Supported types:
    /// </listheader>
    /// <item><description><see cref="List{T}"/></description></item>
    /// <item><description><see cref="IList{T}"/></description></item>
    /// <item><description><see cref="ICollection{T}"/></description></item>
    /// <item><description><see cref="IEnumerable{T}"/></description></item>
    /// <item><description><see cref="IReadOnlyList{T}"/></description></item>
    /// <item><description><see cref="IReadOnlyCollection{T}"/></description></item>
    /// <item><description><see cref="IList"/></description></item>
    /// <item><description><see cref="ICollection"/></description></item>
    /// <item><description><see cref="IEnumerable"/></description></item>
    /// </list>
    /// </summary>
    public static bool TryGetTypeRegistration(
        TypeRegistry registry,
        TypeKind targetTypeKind,
        Type declaredType,
        [NotNullWhen(true)] out TypeRegistration? registration
    )
    {
        if (!TryGetElementType(declaredType, out var elementType))
        {
            registration = null;
            return false;
        }

        var listType = targetTypeKind switch
        {
            TypeKind.BoolArray when elementType is null || elementType == typeof(bool) => typeof(List<bool>),
            TypeKind.Int8Array when elementType is null || elementType == typeof(byte) => typeof(List<byte>),
            TypeKind.Int8Array when elementType == typeof(sbyte) => typeof(List<sbyte>),
            TypeKind.Int16Array when elementType is null || elementType == typeof(short) => typeof(List<short>),
            TypeKind.Int16Array when elementType == typeof(ushort) => typeof(List<ushort>),
            TypeKind.Int32Array when elementType is null || elementType == typeof(int) => typeof(List<int>),
            TypeKind.Int32Array when elementType == typeof(uint) => typeof(List<uint>),
            TypeKind.Int64Array when elementType is null || elementType == typeof(long) => typeof(List<long>),
            TypeKind.Int64Array when elementType == typeof(ulong) => typeof(List<ulong>),
#if NET5_0_OR_GREATER
            TypeKind.Float16Array when elementType is null || elementType == typeof(Half) => typeof(List<Half>),
#endif
            TypeKind.Float32Array when elementType is null || elementType == typeof(float) => typeof(List<float>),
            TypeKind.Float64Array when elementType is null || elementType == typeof(double) => typeof(List<double>),
            TypeKind.List when elementType is not null => typeof(List<>).MakeGenericType(elementType),
            _ => null,
        };

        if (listType is null)
        {
            registration = null;
            return false;
        }

        registration = registry.GetTypeRegistration(listType);
        return true;
    }

    private static bool TryGetElementType(Type declaredType, out Type? elementType)
    {
        if (TypeHelper.GetGenericBaseTypeArguments(declaredType, typeof(List<>), out var argumentTypes))
        {
            elementType = argumentTypes[0];
            return true;
        }

        if (TypeHelper.GetGenericBaseTypeArguments(declaredType, typeof(IList<>), out argumentTypes))
        {
            elementType = argumentTypes[0];
            return true;
        }

        if (TypeHelper.GetGenericBaseTypeArguments(declaredType, typeof(ICollection<>), out argumentTypes))
        {
            elementType = argumentTypes[0];
            return true;
        }

        if (TypeHelper.GetGenericBaseTypeArguments(declaredType, typeof(IEnumerable<>), out argumentTypes))
        {
            elementType = argumentTypes[0];
            return true;
        }

        if (TypeHelper.GetGenericBaseTypeArguments(declaredType, typeof(IReadOnlyList<>), out argumentTypes))
        {
            elementType = argumentTypes[0];
            return true;
        }

        if (TypeHelper.GetGenericBaseTypeArguments(declaredType, typeof(IReadOnlyCollection<>), out argumentTypes))
        {
            elementType = argumentTypes[0];
            return true;
        }

        if (
            typeof(IList).IsAssignableFrom(declaredType)
            || typeof(ICollection).IsAssignableFrom(declaredType)
            || typeof(IEnumerable).IsAssignableFrom(declaredType)
            || declaredType == typeof(object)
        )
        {
            elementType = null;
            return true;
        }

        elementType = null;
        return false;
    }

    private static ISerializer CreateListSerializer<TElement>(TypeRegistration? elementRegistration)
    {
        return new ListSerializer<TElement>(elementRegistration);
    }

    private static IDeserializer CreateListDeserializer<TElement>(TypeRegistration? elementRegistration)
    {
        return new ListDeserializer<TElement>(elementRegistration);
    }
}
