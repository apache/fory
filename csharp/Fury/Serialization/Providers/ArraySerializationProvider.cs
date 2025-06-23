using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Diagnostics.Contracts;
using System.Linq;
using System.Reflection;
using Fury.Context;
using Fury.Meta;

namespace Fury.Serialization;

internal static class ArraySerializationProvider
{
    private static readonly MethodInfo CreateArraySerializerMethod = typeof(ArraySerializationProvider).GetMethod(
        nameof(CreateArraySerializer),
        BindingFlags.NonPublic | BindingFlags.Static
    )!;

    private static readonly MethodInfo CreateArrayDeserializerMethod = typeof(ArraySerializationProvider).GetMethod(
        nameof(CreateArrayDeserializer),
        BindingFlags.NonPublic | BindingFlags.Static
    )!;

    public static bool TryGetType(TypeKind targetTypeKind, Type declaredType, [NotNullWhen(true)] out Type? targetType)
    {
        targetType = null;
        if (!TryGetElementType(targetTypeKind, out var candidateElementTypes))
        {
            return false;
        }

        var arrayType = candidateElementTypes.Item1.MakeArrayType();
        if (!declaredType.IsAssignableFrom(arrayType))
        {
            arrayType = candidateElementTypes.Item2?.MakeArrayType();
            if (!declaredType.IsAssignableFrom(arrayType))
            {
                return false;
            }
        }

        targetType = arrayType;
        return true;
    }

    public static bool TryGetTypeKind(Type targetType, out TypeKind targetTypeKind)
    {
        if (!targetType.IsArray || targetType.GetArrayRank() > 1)
        {
            // Variable bound arrays are not supported yet.
            targetTypeKind = default;
            return false;
        }

        if (targetType == typeof(byte[]))
        {
            targetTypeKind = TypeKind.Int8Array;
        }
        else if (targetType == typeof(sbyte[]))
        {
            targetTypeKind = TypeKind.Int8Array;
        }
        else if (targetType == typeof(short[]))
        {
            targetTypeKind = TypeKind.Int16Array;
        }
        else if (targetType == typeof(ushort[]))
        {
            targetTypeKind = TypeKind.Int16Array;
        }
        else if (targetType == typeof(int[]))
        {
            targetTypeKind = TypeKind.Int32Array;
        }
        else if (targetType == typeof(uint[]))
        {
            targetTypeKind = TypeKind.Int32Array;
        }
        else if (targetType == typeof(long[]))
        {
            targetTypeKind = TypeKind.Int64Array;
        }
        else if (targetType == typeof(ulong[]))
        {
            targetTypeKind = TypeKind.Int64Array;
        }
        else if (targetType == typeof(float[]))
        {
            targetTypeKind = TypeKind.Float32Array;
        }
        else if (targetType == typeof(double[]))
        {
            targetTypeKind = TypeKind.Float64Array;
        }
        else if (targetType == typeof(bool[]))
        {
            targetTypeKind = TypeKind.BoolArray;
        }
        else
        {
            var elementType = targetType.GetElementType();
            if (elementType is { IsArray: true })
            {
                while (elementType is { IsArray: true })
                {
                    elementType = elementType.GetElementType();
                }

                if (elementType is { IsPrimitive: true })
                {
                    switch (Type.GetTypeCode(elementType))
                    {
                        case TypeCode.Byte:
                        case TypeCode.SByte:
                        case TypeCode.Int16:
                        case TypeCode.UInt16:
                        case TypeCode.Int32:
                        case TypeCode.UInt32:
                        case TypeCode.Int64:
                        case TypeCode.UInt64:
                        case TypeCode.Single:
                        case TypeCode.Double:
                        case TypeCode.Boolean:
                            targetTypeKind = TypeKind.Array;
                            return true;
                    }
                }
            }

            targetTypeKind = default;
            return false;
        }

        return true;
    }

    [Pure]
    private static bool TryGetElementType(Type targetType, [NotNullWhen(true)] out Type? candidateElementTypes)
    {
        if (!targetType.IsArray)
        {
            candidateElementTypes = null;
            return false;
        }

        candidateElementTypes = targetType.GetElementType();
        return candidateElementTypes is { IsGenericParameter: false };
    }

    [Pure]
    private static bool TryGetElementType(TypeKind typeKind, out (Type, Type?) candidateElementTypes)
    {
        // TODO: Add support for TypeKind.Array
        candidateElementTypes = typeKind switch
        {
            TypeKind.Int8Array => (typeof(byte), typeof(sbyte)),
            TypeKind.Int16Array => (typeof(short), typeof(ushort)),
            TypeKind.Int32Array => (typeof(int), typeof(uint)),
            TypeKind.Int64Array => (typeof(long), typeof(ulong)),
#if NET8_0_OR_GREATER
            TypeKind.Float16Array => (typeof(Half), null),
#endif
            TypeKind.Float32Array => (typeof(float), null),
            TypeKind.Float64Array => (typeof(double), null),
            TypeKind.BoolArray => (typeof(bool), null),
            _ => default,
        };
        return candidateElementTypes is not (null, null);
    }

    public static bool TryGetSerializerFactory(TypeRegistry registry, Type targetType, [NotNullWhen(true)] out Func<ISerializer>? serializerFactory)
    {
        if (!TryGetElementType(targetType, out var elementType))
        {
            serializerFactory = null;
            return false;
        }

        Func<TypeRegistration?, ISerializer> createMethod =
            (Func<TypeRegistration?, ISerializer>)
                CreateArraySerializerMethod.MakeGenericMethod(elementType).CreateDelegate(typeof(Func<TypeRegistration?, ISerializer>));

        if (elementType.IsSealed)
        {
            var elementRegistration = registry.GetTypeRegistration(elementType);
            serializerFactory = () => createMethod(elementRegistration);
        }
        else
        {
            serializerFactory = () => createMethod(null);
        }

        return true;
    }

    private static ISerializer CreateArraySerializer<TElement>(TypeRegistration? elementRegistration)
        where TElement : notnull
    {
        return new ArraySerializer<TElement>(elementRegistration);
    }

    private static bool TryGetDeserializerFactoryCommon(
        TypeRegistry registry,
        Type elementType,
        [NotNullWhen(true)] out Func<IDeserializer>? deserializerFactory
    )
    {
        var createMethod =
            (Func<TypeRegistration?, IDeserializer>)
                CreateArrayDeserializerMethod.MakeGenericMethod(elementType).CreateDelegate(typeof(Func<TypeRegistration?, IDeserializer>));

        if (elementType.IsSealed)
        {
            var elementRegistration = registry.GetTypeRegistration(elementType);
            deserializerFactory = () => createMethod(elementRegistration);
        }
        else
        {
            deserializerFactory = () => createMethod(null);
        }

        return true;
    }

    public static bool TryGetDeserializerFactory(TypeRegistry registry, Type targetType, [NotNullWhen(true)] out Func<IDeserializer>? deserializerFactory)
    {
        if (!TryGetElementType(targetType, out var elementType))
        {
            deserializerFactory = null;
            return false;
        }

        return TryGetDeserializerFactoryCommon(registry, elementType, out deserializerFactory);
    }

    private static IDeserializer CreateArrayDeserializer<TElement>(TypeRegistration? elementRegistration)
        where TElement : notnull
    {
        return new ArrayDeserializer<TElement>(elementRegistration);
    }
}

internal static class ArrayTypeRegistrationProvider
{
    // Supported types:
    // CustomType[]

    // Unsupported types:
    // any array with more than 1 dimension, e.g. CustomType[,]
    // PrimitiveType[] (supported by builtin serializers and deserializers directly)

    private static readonly MethodInfo CreateArraySerializerMethod = typeof(ArraySerializationProvider).GetMethod(
        nameof(CreateArraySerializer),
        BindingFlags.NonPublic | BindingFlags.Static
    )!;

    private static readonly MethodInfo CreateArrayDeserializerMethod = typeof(ArraySerializationProvider).GetMethod(
        nameof(CreateArrayDeserializer),
        BindingFlags.NonPublic | BindingFlags.Static
    )!;

    public static bool TryRegisterType(TypeRegistry registry, Type targetType, [NotNullWhen(true)] out TypeRegistration? registration)
    {
        if (!TryGetElementType(targetType, out var elementType))
        {
            registration = null;
            return false;
        }
        return TryRegisterTypeCommon(registry, elementType, out registration);
    }

    private static bool TryRegisterTypeCommon(TypeRegistry registry, Type elementType, [NotNullWhen(true)] out TypeRegistration? registration)
    {
        var serializerFactory = CreateArraySerializerMethod.MakeGenericMethod(elementType).CreateDelegate<Func<ISerializer>>();
        var deserializerFactory = CreateArrayDeserializerMethod.MakeGenericMethod(elementType).CreateDelegate<Func<IDeserializer>>();

        registration = registry.Register(elementType.MakeArrayType(), TypeKind.List, serializerFactory, deserializerFactory);
        return true;
    }

    private static bool TryGetElementType(Type type, [NotNullWhen(true)] out Type? elementType)
    {
        if (!type.IsArray)
        {
            elementType = null;
            return false;
        }

        if (type.GetArrayRank() > 1)
        {
            // Variable bound arrays are not supported yet.
            elementType = null;
            return false;
        }

        elementType = type.GetElementType();
        return elementType is not null;
    }

    private static bool TryGetElementTypeByDeclaredType(Type declaredType, [NotNullWhen(true)] out Type? elementType)
    {
        if (declaredType.IsArray)
        {
            if (declaredType.GetArrayRank() > 1)
            {
                // Variable bound arrays are not supported yet.
                elementType = null;
                return false;
            }

            elementType = declaredType.GetElementType();
            return elementType is not null;
        }

        var interfaces = declaredType.GetInterfaces();
        var genericEnumerableInterfaces = interfaces.Where(t => t.IsGenericType && t.GetGenericTypeDefinition() == typeof(IEnumerable<>)).ToList();
        if (genericEnumerableInterfaces.Count > 1)
        {
            // Ambiguous type
            elementType = null;
            return false;
        }

        if (genericEnumerableInterfaces.Count == 0)
        {
            var enumerableInterface = interfaces.FirstOrDefault(t => t == typeof(IEnumerable));
            if (enumerableInterface is not null)
            {
                elementType = typeof(object);
                return true;
            }

            elementType = null;
            return false;
        }

        elementType = genericEnumerableInterfaces[0].GenericTypeArguments[0];
        return true;
    }

    private static bool TryMakeGenericCreateMethod<TDelegate>(
        Type elementType,
        MethodInfo createMethod,
        MethodInfo nullableCreateMethod,
        [NotNullWhen(true)] out TDelegate? factory
    )
        where TDelegate : Delegate
    {
        MethodInfo method;
        if (Nullable.GetUnderlyingType(elementType) is { } underlyingType)
        {
#if NET5_0_OR_GREATER
            if (underlyingType.IsPrimitive || underlyingType == typeof(Half))
#else
            if (underlyingType.IsPrimitive)
#endif
            {
                // Fury does not support nullable primitive types
                factory = null;
                return false;
            }
            elementType = underlyingType;
            method = createMethod;
        }
        else
        {
            method = nullableCreateMethod;
        }

        factory = method.MakeGenericMethod(elementType).CreateDelegate<TDelegate>();
        return true;
    }

    private static ISerializer CreateArraySerializer<TElement>(TypeRegistration? elementRegistration)
        where TElement : notnull
    {
        return new ArraySerializer<TElement>(elementRegistration);
    }

    private static IDeserializer CreateArrayDeserializer<TElement>(TypeRegistration? elementRegistration)
        where TElement : notnull
    {
        return new ArrayDeserializer<TElement>(elementRegistration);
    }
}
