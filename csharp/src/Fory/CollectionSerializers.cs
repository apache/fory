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

namespace Apache.Fory;

internal static class CollectionBits
{
    public const byte TrackingRef = 0b0000_0001;
    public const byte HasNull = 0b0000_0010;
    public const byte DeclaredElementType = 0b0000_0100;
    public const byte SameType = 0b0000_1000;
}


internal static class CollectionCodec
{
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

        bool hasNull = false;
        if (elementSerializer.IsNullableType)
        {
            for (int i = 0; i < list.Count; i++)
            {
                if (!elementSerializer.IsNoneObject(list[i]))
                {
                    continue;
                }

                hasNull = true;
                break;
            }
        }

        bool trackRef = context.TrackRef && elementSerializer.IsReferenceTrackableType;
        bool declaredElementType = hasGenerics && !elementSerializer.StaticTypeId.NeedsTypeInfoForField();
        bool dynamicElementType = elementSerializer.StaticTypeId == TypeId.Unknown;

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
                        throw new RefException($"invalid nullability flag {refFlag}");
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

internal static class DynamicContainerCodec
{
    public static bool TryGetTypeId(object value, out TypeId typeId)
    {
        if (value is IDictionary)
        {
            typeId = TypeId.Map;
            return true;
        }

        Type valueType = value.GetType();
        if (value is IList && !valueType.IsArray)
        {
            typeId = TypeId.List;
            return true;
        }

        if (IsSet(valueType))
        {
            typeId = TypeId.Set;
            return true;
        }

        typeId = default;
        return false;
    }

    public static bool TryWritePayload(object value, ref WriteContext context, bool hasGenerics)
    {
        if (value is IDictionary dictionary)
        {
            NullableKeyDictionary<object, object?> map = new();
            foreach (DictionaryEntry entry in dictionary)
            {
                map.Add(entry.Key, entry.Value);
            }

            context.TypeResolver.GetSerializer<NullableKeyDictionary<object, object?>>().WriteData(ref context, map, false);
            return true;
        }

        Type valueType = value.GetType();
        if (value is IList list && !valueType.IsArray)
        {
            List<object?> values = new(list.Count);
            for (int i = 0; i < list.Count; i++)
            {
                values.Add(list[i]);
            }

            context.TypeResolver.GetSerializer<List<object?>>().WriteData(ref context, values, hasGenerics);
            return true;
        }

        if (!IsSet(valueType))
        {
            return false;
        }

        HashSet<object?> set = [];
        foreach (object? item in (IEnumerable)value)
        {
            set.Add(item);
        }

        context.TypeResolver.GetSerializer<HashSet<object?>>().WriteData(ref context, set, hasGenerics);
        return true;
    }

    public static List<object?> ReadListPayload(ref ReadContext context)
    {
        return context.TypeResolver.GetSerializer<List<object?>>().ReadData(ref context);
    }

    public static HashSet<object?> ReadSetPayload(ref ReadContext context)
    {
        return context.TypeResolver.GetSerializer<HashSet<object?>>().ReadData(ref context);
    }

    public static object ReadMapPayload(ref ReadContext context)
    {
        NullableKeyDictionary<object, object?> map = context.TypeResolver.GetSerializer<NullableKeyDictionary<object, object?>>().ReadData(ref context);
        if (map.HasNullKey)
        {
            return map;
        }

        return new Dictionary<object, object?>(map.NonNullEntries);
    }

    private static bool IsSet(Type valueType)
    {
        if (!valueType.IsGenericType)
        {
            return false;
        }

        if (valueType.GetGenericTypeDefinition() == typeof(ISet<>))
        {
            return true;
        }

        foreach (Type iface in valueType.GetInterfaces())
        {
            if (!iface.IsGenericType)
            {
                continue;
            }

            if (iface.GetGenericTypeDefinition() == typeof(ISet<>))
            {
                return true;
            }
        }

        return false;
    }
}

public sealed class ArraySerializer<T> : TypedSerializer<T[]>
{
    public override TypeId StaticTypeId => TypeId.List;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override T[] DefaultValue => null!;
    public override bool IsNone(in T[] value) => value is null;

    public override void WriteData(ref WriteContext context, in T[] value, bool hasGenerics)
    {
        T[] safe = value ?? [];
        CollectionCodec.WriteCollectionData(
            safe,
            context.TypeResolver.GetSerializer<T>(),
            ref context,
            hasGenerics);
    }

    public override T[] ReadData(ref ReadContext context)
    {
        List<T> values = CollectionCodec.ReadCollectionData<T>(context.TypeResolver.GetSerializer<T>(), ref context);
        return values.ToArray();
    }
}

public class ListSerializer<T> : TypedSerializer<List<T>>
{
    public override TypeId StaticTypeId => TypeId.List;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override List<T> DefaultValue => null!;
    public override bool IsNone(in List<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<T> value, bool hasGenerics)
    {
        List<T> safe = value ?? [];
        CollectionCodec.WriteCollectionData(safe, context.TypeResolver.GetSerializer<T>(), ref context, hasGenerics);
    }

    public override List<T> ReadData(ref ReadContext context)
    {
        return CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), ref context);
    }
}

public sealed class SetSerializer<T> : TypedSerializer<HashSet<T>> where T : notnull
{
    public override TypeId StaticTypeId => TypeId.Set;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override HashSet<T> DefaultValue => null!;
    public override bool IsNone(in HashSet<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<T> value, bool hasGenerics)
    {
        List<T> list = value is null ? [] : [.. value];
        context.TypeResolver.GetSerializer<List<T>>().WriteData(ref context, list, hasGenerics);
    }

    public override HashSet<T> ReadData(ref ReadContext context)
    {
        return [.. context.TypeResolver.GetSerializer<List<T>>().ReadData(ref context)];
    }
}
