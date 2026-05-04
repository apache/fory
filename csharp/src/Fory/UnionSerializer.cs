// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

using System.Collections;
using System.Linq.Expressions;
using System.Reflection;

namespace Apache.Fory;

public sealed class UnionSerializer<TUnion> : Serializer<TUnion>
    where TUnion : Union
{
    private static readonly Func<int, object?, TUnion> Factory = BuildFactory();
    private static readonly IReadOnlyDictionary<int, Type> CaseTypeByIndex = BuildCaseTypeMap();

    public override TUnion DefaultValue => null!;

    public override void WriteData(WriteContext context, in TUnion value, bool hasGenerics)
    {
        _ = hasGenerics;
        if (value is null)
        {
            throw new InvalidDataException("union value is null");
        }

        context.Writer.WriteVarUInt32((uint)value.Index);
        if (CaseTypeByIndex.TryGetValue(value.Index, out Type? caseType))
        {
            WriteTypedCaseValue(context, caseType, value.Value);
            return;
        }

        DynamicAnyCodec.WriteAny(context, value.Value, RefMode.Tracking, true, false);
    }

    public override TUnion ReadData(ReadContext context)
    {
        uint rawCaseId = context.Reader.ReadVarUInt32();
        if (rawCaseId > int.MaxValue)
        {
            throw new InvalidDataException($"union case id out of range: {rawCaseId}");
        }

        int caseId = (int)rawCaseId;
        object? caseValue;
        if (CaseTypeByIndex.TryGetValue(caseId, out Type? caseType))
        {
            caseValue = ReadTypedCaseValue(context, caseType);
        }
        else
        {
            caseValue = DynamicAnyCodec.ReadAny(context, RefMode.Tracking, true);
        }

        return Factory(caseId, caseValue);
    }

    private static Func<int, object?, TUnion> BuildFactory()
    {
        if (typeof(TUnion) == typeof(Union))
        {
            return (index, value) => (TUnion)(object)new Union(index, value);
        }

        ConstructorInfo? ctor = typeof(TUnion).GetConstructor(
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            binder: null,
            [typeof(int), typeof(object)],
            modifiers: null);
        if (ctor is not null)
        {
            ParameterExpression indexParam = Expression.Parameter(typeof(int), "index");
            ParameterExpression valueParam = Expression.Parameter(typeof(object), "value");
            NewExpression created = Expression.New(ctor, indexParam, valueParam);
            return Expression.Lambda<Func<int, object?, TUnion>>(created, indexParam, valueParam).Compile();
        }

        MethodInfo? ofFactory = typeof(TUnion).GetMethod(
            "Of",
            BindingFlags.Public | BindingFlags.Static,
            binder: null,
            [typeof(int), typeof(object)],
            modifiers: null);
        if (ofFactory is not null && typeof(TUnion).IsAssignableFrom(ofFactory.ReturnType))
        {
            return (index, value) => (TUnion)ofFactory.Invoke(null, [index, value])!;
        }

        throw new InvalidDataException(
            $"union type {typeof(TUnion)} must define (int, object) constructor or static Of(int, object)");
    }

    private static IReadOnlyDictionary<int, Type> BuildCaseTypeMap()
    {
        if (typeof(TUnion) == typeof(Union))
        {
            return new Dictionary<int, Type>();
        }

        Dictionary<int, Type> caseTypes = new();
        MethodInfo[] methods = typeof(TUnion).GetMethods(BindingFlags.Public | BindingFlags.Static);
        foreach (MethodInfo method in methods)
        {
            if (!typeof(TUnion).IsAssignableFrom(method.ReturnType))
            {
                continue;
            }

            ParameterInfo[] parameters = method.GetParameters();
            if (parameters.Length != 1)
            {
                continue;
            }

            Type caseType = parameters[0].ParameterType;
            if (!TryResolveCaseIndex(method, caseType, out int caseIndex))
            {
                continue;
            }

            caseTypes.TryAdd(caseIndex, caseType);
        }

        return caseTypes;
    }

    private static bool TryResolveCaseIndex(MethodInfo method, Type caseType, out int caseIndex)
    {
        caseIndex = default;
        object? probeArg = CreateProbeArgument(caseType);
        try
        {
            object? result = method.Invoke(null, [probeArg]);
            if (result is not Union union)
            {
                return false;
            }

            caseIndex = union.Index;
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static object? CreateProbeArgument(Type caseType)
    {
        if (!caseType.IsValueType)
        {
            return null;
        }

        return Activator.CreateInstance(caseType);
    }

    private static void WriteTypedCaseValue(WriteContext context, Type caseType, object? value)
    {
        object? normalized = NormalizeCaseValue(value, caseType);
        TypeInfo typeInfo = context.TypeResolver.GetTypeInfo(caseType);
        context.TypeResolver.WriteObject(
            typeInfo,
            context,
            normalized,
            RefMode.Tracking,
            writeTypeInfo: true,
            hasGenerics: caseType.IsGenericType);
    }

    private static object? ReadTypedCaseValue(ReadContext context, Type caseType)
    {
        TypeInfo typeInfo = context.TypeResolver.GetTypeInfo(caseType);
        object? value = context.TypeResolver.ReadObject(typeInfo, context, RefMode.Tracking, readTypeInfo: true);
        return NormalizeCaseValue(value, caseType);
    }

    private static object? NormalizeCaseValue(object? value, Type targetType)
    {
        if (value is null || targetType.IsInstanceOfType(value))
        {
            return value;
        }

        if (TryConvertMapValue(value, targetType, out object? converted))
        {
            return converted;
        }

        if (TryConvertListValue(value, targetType, out converted))
        {
            return converted;
        }

        return value;
    }

    private static bool TryConvertListValue(object value, Type targetType, out object? converted)
    {
        converted = null;
        if (!TryGetListElementType(targetType, out Type? elementType))
        {
            return false;
        }

        if (value is not IEnumerable source)
        {
            return false;
        }

        List<object?> items = [];
        foreach (object? item in source)
        {
            items.Add(ConvertCaseValue(item, elementType!));
        }

        if (targetType.IsArray)
        {
            Array typedArray = Array.CreateInstance(elementType!, items.Count);
            for (int i = 0; i < items.Count; i++)
            {
                typedArray.SetValue(items[i], i);
            }

            converted = typedArray;
            return true;
        }

        IList typedList = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(elementType!))!;
        foreach (object? item in items)
        {
            typedList.Add(item);
        }

        converted = typedList;
        return true;
    }

    private static bool TryConvertMapValue(object value, Type targetType, out object? converted)
    {
        converted = null;
        if (!TryGetMapTypes(targetType, out Type? keyType, out Type? valueType))
        {
            return false;
        }

        IDictionary typedMap =
            (IDictionary)Activator.CreateInstance(typeof(Dictionary<,>).MakeGenericType(keyType!, valueType!))!;
        if (value is IDictionary dictionary)
        {
            foreach (DictionaryEntry entry in dictionary)
            {
                object key = ConvertCaseValue(entry.Key, keyType!) ??
                             throw new InvalidDataException("union map key is null");
                typedMap.Add(key, ConvertCaseValue(entry.Value, valueType!));
            }

            converted = typedMap;
            return true;
        }

        if (value is not IEnumerable entries)
        {
            return false;
        }

        foreach (object? entry in entries)
        {
            if (entry is null || !TryGetKeyValue(entry, out object? key, out object? itemValue))
            {
                return false;
            }

            object convertedKey = ConvertCaseValue(key, keyType!) ??
                                  throw new InvalidDataException("union map key is null");
            typedMap.Add(convertedKey, ConvertCaseValue(itemValue, valueType!));
        }

        converted = typedMap;
        return true;
    }

    private static bool TryGetListElementType(Type targetType, out Type? elementType)
    {
        if (targetType.IsArray)
        {
            elementType = targetType.GetElementType();
            return elementType is not null;
        }

        if (targetType.IsGenericType && targetType.GetGenericTypeDefinition() == typeof(List<>))
        {
            elementType = targetType.GetGenericArguments()[0];
            return true;
        }

        foreach (Type iface in targetType.GetInterfaces())
        {
            if (!iface.IsGenericType)
            {
                continue;
            }

            Type genericDef = iface.GetGenericTypeDefinition();
            if (genericDef == typeof(IList<>) || genericDef == typeof(IReadOnlyList<>) || genericDef == typeof(IEnumerable<>))
            {
                elementType = iface.GetGenericArguments()[0];
                return true;
            }
        }

        elementType = null;
        return false;
    }

    private static bool TryGetMapTypes(Type targetType, out Type? keyType, out Type? valueType)
    {
        if (targetType.IsGenericType)
        {
            Type genericDef = targetType.GetGenericTypeDefinition();
            if (genericDef == typeof(Dictionary<,>) ||
                genericDef == typeof(IDictionary<,>) ||
                genericDef == typeof(IReadOnlyDictionary<,>) ||
                genericDef == typeof(SortedDictionary<,>) ||
                genericDef == typeof(SortedList<,>) ||
                genericDef == typeof(NullableKeyDictionary<,>))
            {
                Type[] args = targetType.GetGenericArguments();
                keyType = args[0];
                valueType = args[1];
                return true;
            }
        }

        foreach (Type iface in targetType.GetInterfaces())
        {
            if (!iface.IsGenericType)
            {
                continue;
            }

            Type genericDef = iface.GetGenericTypeDefinition();
            if (genericDef == typeof(IDictionary<,>) || genericDef == typeof(IReadOnlyDictionary<,>))
            {
                Type[] args = iface.GetGenericArguments();
                keyType = args[0];
                valueType = args[1];
                return true;
            }
        }

        keyType = null;
        valueType = null;
        return false;
    }

    private static bool TryGetKeyValue(object entry, out object? key, out object? value)
    {
        Type entryType = entry.GetType();
        PropertyInfo? keyProperty = entryType.GetProperty("Key");
        PropertyInfo? valueProperty = entryType.GetProperty("Value");
        if (keyProperty is null || valueProperty is null)
        {
            key = null;
            value = null;
            return false;
        }

        key = keyProperty.GetValue(entry);
        value = valueProperty.GetValue(entry);
        return true;
    }

    private static object? ConvertCaseValue(object? value, Type elementType)
    {
        if (value is null || elementType.IsInstanceOfType(value))
        {
            return value;
        }

        if (TryConvertMapValue(value, elementType, out object? convertedMap))
        {
            return convertedMap;
        }

        if (TryConvertListValue(value, elementType, out object? convertedList))
        {
            return convertedList;
        }

        Type target = Nullable.GetUnderlyingType(elementType) ?? elementType;
        try
        {
            return Convert.ChangeType(value, target);
        }
        catch
        {
            return value;
        }
    }
}
