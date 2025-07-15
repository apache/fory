using System;
using System.Diagnostics;
using System.Diagnostics.CodeAnalysis;
using System.Reflection;
using System.Runtime.CompilerServices;

namespace Fury.Helpers;

internal static class TypeHelper<T>
{
    public static readonly bool IsSealed = typeof(T).IsSealed;
    public static readonly bool IsReferenceOrContainsReferences = TypeHelper.IsReferenceOrContainsReferences<T>();
}

internal static class TypeHelper
{
    public static bool GetGenericBaseTypeArguments(
        Type targetType,
        Type genericBaseType,
        [NotNullWhen(true)] out Type[]? argument
    )
    {
        Debug.Assert(genericBaseType.IsGenericType);
        genericBaseType = genericBaseType.GetGenericTypeDefinition();

        if (genericBaseType.IsInterface)
        {
            foreach (var @interface in targetType.GetInterfaces())
            {
                if (@interface.IsGenericType && @interface.GetGenericTypeDefinition() == genericBaseType)
                {
                    argument = @interface.GenericTypeArguments;
                    return true;
                }
            }
        }
        else
        {
            var baseType = targetType;
            while (baseType is not null)
            {
                if (baseType.IsGenericType && baseType.GetGenericTypeDefinition() == genericBaseType)
                {
                    argument = baseType.GenericTypeArguments;
                    return true;
                }
                baseType = targetType.BaseType;
            }
        }

        argument = null;
        return false;
    }

    public static bool TryGetUnderlyingElementType(
        Type arrayType,
        [NotNullWhen(true)] out Type? elementType,
        out int rank
    )
    {
        // TODO: Multi-dimensional arrays are not supported yet.
        rank = 0;
        var currentType = arrayType;
        while (currentType.IsArray)
        {
            elementType = currentType.GetElementType();
            if (elementType is null)
            {
                return false;
            }
            currentType = elementType;
            rank++;
        }
        elementType = currentType;
        return true;
    }

    public static bool IsReferenceOrContainsReferences(Type type)
    {
        if (!type.IsValueType)
        {
            return true;
        }

        if (type.IsPrimitive || type.IsEnum)
        {
            return false;
        }

        foreach (var field in type.GetFields(BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public))
        {
            if (IsReferenceOrContainsReferences(field.FieldType))
            {
                return true;
            }
        }

        return false;
    }

    public static bool IsReferenceOrContainsReferences<T>()
    {
#if NET8_0_OR_GREATER
        return RuntimeHelpers.IsReferenceOrContainsReferences<T>();
#else
        return IsReferenceOrContainsReferences(typeof(T));
#endif
    }

    public static bool IsNullable(Type type)
    {
        return Nullable.GetUnderlyingType(type) is not null;
    }
}
