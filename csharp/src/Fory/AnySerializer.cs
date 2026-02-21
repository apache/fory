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

namespace Apache.Fory;

public readonly struct DynamicAnyObjectSerializer : IStaticSerializer<DynamicAnyObjectSerializer, object?>
{
    public static ForyTypeId StaticTypeId => ForyTypeId.Unknown;
    public static bool IsNullableType => true;
    public static bool IsReferenceTrackableType => true;
    public static object? DefaultValue => null;
    public static bool IsNone(in object? value) => value is null;

    public static void WriteData(ref WriteContext context, in object? value, bool hasGenerics)
    {
        if (IsNone(value))
        {
            return;
        }

        DynamicAnyCodec.WriteAnyPayload(value!, ref context, hasGenerics);
    }

    public static object? ReadData(ref ReadContext context)
    {
        DynamicTypeInfo? dynamicTypeInfo = context.DynamicTypeInfo(typeof(object));
        if (dynamicTypeInfo is null)
        {
            throw new ForyInvalidDataException("dynamic Any value requires type info");
        }

        return context.TypeResolver.ReadDynamicValue(dynamicTypeInfo, ref context);
    }

    public static void WriteTypeInfo(ref WriteContext context)
    {
        throw new ForyInvalidDataException("dynamic Any value type info is runtime-only");
    }

    public static void ReadTypeInfo(ref ReadContext context)
    {
        DynamicTypeInfo typeInfo = context.TypeResolver.ReadDynamicTypeInfo(ref context);
        context.SetDynamicTypeInfo(typeof(object), typeInfo);
    }

    public static void Write(ref WriteContext context, in object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
    {
        if (refMode != RefMode.None)
        {
            if (IsNone(value))
            {
                context.Writer.WriteInt8((sbyte)RefFlag.Null);
                return;
            }

            bool wroteTrackingRefFlag = false;
            if (refMode == RefMode.Tracking && AnyValueIsReferenceTrackable(value!))
            {
                if (context.RefWriter.TryWriteReference(context.Writer, value!))
                {
                    return;
                }

                wroteTrackingRefFlag = true;
            }

            if (!wroteTrackingRefFlag)
            {
                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
            }
        }

        if (writeTypeInfo)
        {
            DynamicAnyCodec.WriteAnyTypeInfo(value!, ref context);
        }

        WriteData(ref context, value, hasGenerics);
    }

    public static object? Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        if (refMode != RefMode.None)
        {
            sbyte rawFlag = context.Reader.ReadInt8();
            RefFlag flag = (RefFlag)rawFlag;
            switch (flag)
            {
                case RefFlag.Null:
                    return null;
                case RefFlag.Ref:
                {
                    uint refId = context.Reader.ReadVarUInt32();
                    return context.RefReader.ReadRefValue(refId);
                }
                case RefFlag.RefValue:
                {
                    uint reservedRefId = context.RefReader.ReserveRefId();
                    context.PushPendingReference(reservedRefId);
                    if (readTypeInfo)
                    {
                        ReadTypeInfo(ref context);
                    }

                    object? value = ReadData(ref context);
                    if (readTypeInfo)
                    {
                        context.ClearDynamicTypeInfo(typeof(object));
                    }

                    context.FinishPendingReferenceIfNeeded(value);
                    context.PopPendingReference();
                    return value;
                }
                case RefFlag.NotNullValue:
                    break;
                default:
                    throw new ForyRefException($"invalid ref flag {rawFlag}");
            }
        }

        if (readTypeInfo)
        {
            ReadTypeInfo(ref context);
        }

        object? result = ReadData(ref context);
        if (readTypeInfo)
        {
            context.ClearDynamicTypeInfo(typeof(object));
        }

        return result;
    }

    private static bool AnyValueIsReferenceTrackable(object value)
    {
        SerializerBinding serializer = SerializerRegistry.GetBinding(value.GetType());
        return serializer.IsReferenceTrackableType;
    }

}

public static class DynamicAnyCodec
{
    internal static void WriteAnyTypeInfo(object value, ref WriteContext context)
    {
        if (value is IList<object?>)
        {
            context.Writer.WriteUInt8((byte)ForyTypeId.List);
            return;
        }

        if (value is ISet<object?>)
        {
            context.Writer.WriteUInt8((byte)ForyTypeId.Set);
            return;
        }

        if (value is IDictionary<string, object?> ||
            value is IDictionary<int, object?> ||
            value is IDictionary<object, object?>)
        {
            context.Writer.WriteUInt8((byte)ForyTypeId.Map);
            return;
        }

        SerializerBinding serializer = SerializerRegistry.GetBinding(value.GetType());
        serializer.WriteTypeInfo(ref context);
    }

    public static object? CastAnyDynamicValue(object? value, Type targetType)
    {
        if (value is null)
        {
            if (targetType == typeof(object))
            {
                return null;
            }

            if (!targetType.IsValueType || Nullable.GetUnderlyingType(targetType) is not null)
            {
                return null;
            }

            throw new ForyInvalidDataException($"cannot cast null dynamic Any value to non-nullable {targetType}");
        }

        if (targetType.IsInstanceOfType(value))
        {
            return value;
        }

        throw new ForyInvalidDataException($"cannot cast dynamic Any value to {targetType}");
    }

    public static void WriteAny(ref WriteContext context, object? value, RefMode refMode, bool writeTypeInfo = true, bool hasGenerics = false)
    {
        SerializerRegistry.Get<object?>().Write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public static object? ReadAny(ref ReadContext context, RefMode refMode, bool readTypeInfo = true)
    {
        return SerializerRegistry.Get<object?>().Read(ref context, refMode, readTypeInfo);
    }

    public static void WriteAnyList(ref WriteContext context, IList<object?>? value, RefMode refMode, bool writeTypeInfo = false, bool hasGenerics = true)
    {
        List<object?>? wrapped = value is null ? null : [.. value];
        Serializer<List<object?>> serializer = SerializerRegistry.Get<List<object?>>();
        serializer.Write(ref context, wrapped!, refMode, writeTypeInfo, hasGenerics);
    }

    public static List<object?>? ReadAnyList(ref ReadContext context, RefMode refMode, bool readTypeInfo = false)
    {
        Serializer<List<object?>> serializer = SerializerRegistry.Get<List<object?>>();
        return serializer.Read(ref context, refMode, readTypeInfo);
    }

    public static void WriteAnySet(ref WriteContext context, ISet<object?>? value, RefMode refMode, bool writeTypeInfo = false, bool hasGenerics = true)
    {
        HashSet<object?>? wrapped = value is null ? null : [.. value];
        Serializer<HashSet<object?>> serializer = SerializerRegistry.Get<HashSet<object?>>();
        serializer.Write(ref context, wrapped!, refMode, writeTypeInfo, hasGenerics);
    }

    public static HashSet<object?>? ReadAnySet(ref ReadContext context, RefMode refMode, bool readTypeInfo = false)
    {
        Serializer<HashSet<object?>> serializer = SerializerRegistry.Get<HashSet<object?>>();
        return serializer.Read(ref context, refMode, readTypeInfo);
    }

    public static void WriteStringAnyMap(ref WriteContext context, IDictionary<string, object?>? value, RefMode refMode, bool writeTypeInfo = false, bool hasGenerics = true)
    {
        Dictionary<string, object?>? wrapped = value is null ? null : new Dictionary<string, object?>(value);
        Serializer<Dictionary<string, object?>> serializer = SerializerRegistry.Get<Dictionary<string, object?>>();
        serializer.Write(ref context, wrapped!, refMode, writeTypeInfo, hasGenerics);
    }

    public static Dictionary<string, object?>? ReadStringAnyMap(ref ReadContext context, RefMode refMode, bool readTypeInfo = false)
    {
        Serializer<Dictionary<string, object?>> serializer = SerializerRegistry.Get<Dictionary<string, object?>>();
        return serializer.Read(ref context, refMode, readTypeInfo);
    }

    public static void WriteInt32AnyMap(ref WriteContext context, IDictionary<int, object?>? value, RefMode refMode, bool writeTypeInfo = false, bool hasGenerics = true)
    {
        Dictionary<int, object?>? wrapped = value is null ? null : new Dictionary<int, object?>(value);
        Serializer<Dictionary<int, object?>> serializer = SerializerRegistry.Get<Dictionary<int, object?>>();
        serializer.Write(ref context, wrapped!, refMode, writeTypeInfo, hasGenerics);
    }

    public static Dictionary<int, object?>? ReadInt32AnyMap(ref ReadContext context, RefMode refMode, bool readTypeInfo = false)
    {
        Serializer<Dictionary<int, object?>> serializer = SerializerRegistry.Get<Dictionary<int, object?>>();
        return serializer.Read(ref context, refMode, readTypeInfo);
    }

    public static void WriteObjectAnyMap(ref WriteContext context, IDictionary<object, object?>? value, RefMode refMode, bool writeTypeInfo = false, bool hasGenerics = true)
    {
        Dictionary<object, object?>? wrapped = value is null ? null : new Dictionary<object, object?>(value);
        Serializer<Dictionary<object, object?>> serializer = SerializerRegistry.Get<Dictionary<object, object?>>();
        serializer.Write(ref context, wrapped!, refMode, writeTypeInfo, hasGenerics);
    }

    public static Dictionary<object, object?>? ReadObjectAnyMap(ref ReadContext context, RefMode refMode, bool readTypeInfo = false)
    {
        Serializer<Dictionary<object, object?>> serializer = SerializerRegistry.Get<Dictionary<object, object?>>();
        return serializer.Read(ref context, refMode, readTypeInfo);
    }

    public static object ReadDynamicAnyMapValue(ref ReadContext context)
    {
        ForyMap<object, object?> map =
            SerializerRegistry.Get<ForyMap<object, object?>>().Read(ref context, RefMode.None, false);
        if (map.HasNullKey)
        {
            return map;
        }

        return new Dictionary<object, object?>(map.NonNullEntries);
    }

    public static void WriteAnyPayload(object value, ref WriteContext context, bool hasGenerics)
    {
        if (value is IList<object?> list)
        {
            WriteAnyList(ref context, list, RefMode.None, false, hasGenerics);
            return;
        }

        if (value is ISet<object?> set)
        {
            WriteAnySet(ref context, set, RefMode.None, false, hasGenerics);
            return;
        }

        if (value is IDictionary<string, object?> stringMap)
        {
            WriteStringAnyMap(ref context, stringMap, RefMode.None, false, false);
            return;
        }

        if (value is IDictionary<int, object?> intMap)
        {
            WriteInt32AnyMap(ref context, intMap, RefMode.None, false, false);
            return;
        }

        if (value is IDictionary<object, object?> objectMap)
        {
            WriteObjectAnyMap(ref context, objectMap, RefMode.None, false, false);
            return;
        }

        SerializerBinding serializer = SerializerRegistry.GetBinding(value.GetType());
        serializer.WriteData(ref context, value, hasGenerics);
    }

    public static void WriteCollectionData<T>(
        IEnumerable<T> values,
        Serializer<T> elementSerializer,
        ref WriteContext context,
        bool hasGenerics)
    {
        List<T> list = values as List<T> ?? [.. values];
        context.Writer.WriteVarUInt32((uint)list.Count);
        if (list.Count == 0)
        {
            return;
        }

        bool hasNull = elementSerializer.IsNullableType && list.Any(v => elementSerializer.IsNoneObject(v));
        bool trackRef = context.TrackRef && elementSerializer.IsReferenceTrackableType;
        bool declaredElementType = hasGenerics && !elementSerializer.StaticTypeId.NeedsTypeInfoForField();
        bool dynamicElementType = elementSerializer.StaticTypeId == ForyTypeId.Unknown;

        byte header = dynamicElementType ? (byte)0 : CollectionBits.SameType;
        if (trackRef)
        {
            header |= CollectionBits.TrackingRef;
        }

        if (hasNull)
        {
            header |= CollectionBits.HasNull;
        }

        if (declaredElementType)
        {
            header |= CollectionBits.DeclaredElementType;
        }

        context.Writer.WriteUInt8(header);
        if (!dynamicElementType && !declaredElementType)
        {
            elementSerializer.WriteTypeInfo(ref context);
        }

        if (dynamicElementType)
        {
            RefMode refMode = trackRef ? RefMode.Tracking : hasNull ? RefMode.NullOnly : RefMode.None;
            foreach (T element in list)
            {
                elementSerializer.Write(ref context, element, refMode, true, hasGenerics);
            }

            return;
        }

        if (trackRef)
        {
            foreach (T element in list)
            {
                elementSerializer.Write(ref context, element, RefMode.Tracking, false, hasGenerics);
            }

            return;
        }

        if (hasNull)
        {
            foreach (T element in list)
            {
                if (elementSerializer.IsNoneObject(element))
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                }
                else
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
                    elementSerializer.WriteData(ref context, element, hasGenerics);
                }
            }

            return;
        }

        foreach (T element in list)
        {
            elementSerializer.WriteData(ref context, element, hasGenerics);
        }
    }

    public static List<T> ReadCollectionData<T>(Serializer<T> elementSerializer, ref ReadContext context)
    {
        int length = checked((int)context.Reader.ReadVarUInt32());
        if (length == 0)
        {
            return [];
        }

        byte header = context.Reader.ReadUInt8();
        bool trackRef = (header & CollectionBits.TrackingRef) != 0;
        bool hasNull = (header & CollectionBits.HasNull) != 0;
        bool declared = (header & CollectionBits.DeclaredElementType) != 0;
        bool sameType = (header & CollectionBits.SameType) != 0;
        bool canonicalizeElements = context.TrackRef && !trackRef && elementSerializer.IsReferenceTrackableType;

        List<T> values = new(length);
        if (!sameType)
        {
            if (trackRef)
            {
                for (int i = 0; i < length; i++)
                {
                    values.Add(elementSerializer.Read(ref context, RefMode.Tracking, true));
                }

                return values;
            }

            if (hasNull)
            {
                for (int i = 0; i < length; i++)
                {
                    sbyte refFlag = context.Reader.ReadInt8();
                    if (refFlag == (sbyte)RefFlag.Null)
                    {
                        values.Add((T)elementSerializer.DefaultObject!);
                    }
                    else if (refFlag == (sbyte)RefFlag.NotNullValue)
                    {
                        values.Add(ReadCollectionElementWithCanonicalization(elementSerializer, ref context, true, canonicalizeElements));
                    }
                    else
                    {
                        throw new ForyRefException($"invalid nullability flag {refFlag}");
                    }
                }
            }
            else
            {
                for (int i = 0; i < length; i++)
                {
                    values.Add(ReadCollectionElementWithCanonicalization(elementSerializer, ref context, true, canonicalizeElements));
                }
            }

            return values;
        }

        if (!declared)
        {
            elementSerializer.ReadTypeInfo(ref context);
        }

        if (trackRef)
        {
                for (int i = 0; i < length; i++)
                {
                    values.Add(elementSerializer.Read(ref context, RefMode.Tracking, false));
                }

            if (!declared)
            {
                context.ClearDynamicTypeInfo(typeof(T));
            }

            return values;
        }

        if (hasNull)
        {
            for (int i = 0; i < length; i++)
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    values.Add((T)elementSerializer.DefaultObject!);
                }
                else
                {
                    values.Add(ReadCollectionElementDataWithCanonicalization(elementSerializer, ref context, canonicalizeElements));
                }
            }
        }
        else
        {
            for (int i = 0; i < length; i++)
            {
                values.Add(ReadCollectionElementDataWithCanonicalization(elementSerializer, ref context, canonicalizeElements));
            }
        }

        if (!declared)
        {
            context.ClearDynamicTypeInfo(typeof(T));
        }

        return values;
    }

    private static T ReadCollectionElementWithCanonicalization<T>(
        Serializer<T> elementSerializer,
        ref ReadContext context,
        bool readTypeInfo,
        bool canonicalize)
    {
        if (!canonicalize)
        {
            return elementSerializer.Read(ref context, RefMode.None, readTypeInfo);
        }

        int start = context.Reader.Cursor;
        T value = elementSerializer.Read(ref context, RefMode.None, readTypeInfo);
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }

    private static T ReadCollectionElementDataWithCanonicalization<T>(
        Serializer<T> elementSerializer,
        ref ReadContext context,
        bool canonicalize)
    {
        if (!canonicalize)
        {
            return elementSerializer.ReadData(ref context);
        }

        int start = context.Reader.Cursor;
        T value = elementSerializer.ReadData(ref context);
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }
}
