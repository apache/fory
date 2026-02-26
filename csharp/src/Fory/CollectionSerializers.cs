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
using System.Collections.Immutable;

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
    private static bool CanDeclareElementType<T>(TypeInfo typeInfo)
    {
        if (typeInfo.IsBuiltinType)
        {
            return true;
        }

        if (!typeInfo.NeedsTypeInfoForField())
        {
            return true;
        }

        return typeof(T).IsSealed;
    }

    public static void WriteCollectionData<T>(
        IEnumerable<T> values,
        Serializer<T> elementSerializer,
        WriteContext context,
        bool hasGenerics)
    {
        TypeInfo elementTypeInfo = context.TypeResolver.GetTypeInfo<T>();
        List<T> list = values as List<T> ?? [.. values];
        context.Writer.WriteVarUInt32((uint)list.Count);
        if (list.Count == 0)
        {
            return;
        }

        bool hasNull = false;
        if (elementTypeInfo.IsNullableType)
        {
            for (int i = 0; i < list.Count; i++)
            {
                if (!context.TypeResolver.IsNoneObject(elementTypeInfo, list[i]))
                {
                    continue;
                }

                hasNull = true;
                break;
            }
        }

        bool trackRef = context.TrackRef && elementTypeInfo.IsReferenceTrackableType;
        bool declaredElementType = hasGenerics && CanDeclareElementType<T>(elementTypeInfo);
        bool dynamicElementType = elementTypeInfo.IsDynamicType;
        bool writeDeclaredCompatibleTypeInfo =
            context.Compatible &&
            declaredElementType &&
            !dynamicElementType &&
            elementTypeInfo.NeedsTypeInfoForField();

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
        if (!dynamicElementType && (!declaredElementType || writeDeclaredCompatibleTypeInfo))
        {
            context.TypeResolver.WriteTypeInfo(elementSerializer, context);
        }

        if (dynamicElementType)
        {
            RefMode refMode = trackRef ? RefMode.Tracking : hasNull ? RefMode.NullOnly : RefMode.None;
            foreach (T element in list)
            {
                elementSerializer.Write(context, element, refMode, true, hasGenerics);
            }

            return;
        }

        if (trackRef)
        {
            foreach (T element in list)
            {
                elementSerializer.Write(context, element, RefMode.Tracking, false, hasGenerics);
            }

            return;
        }

        if (hasNull)
        {
            foreach (T element in list)
            {
                if (context.TypeResolver.IsNoneObject(elementTypeInfo, element))
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                }
                else
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
                    elementSerializer.WriteData(context, element, hasGenerics);
                }
            }

            return;
        }

        foreach (T element in list)
        {
            elementSerializer.WriteData(context, element, hasGenerics);
        }
    }

    public static List<T> ReadCollectionData<T>(Serializer<T> elementSerializer, ReadContext context)
    {
        TypeInfo elementTypeInfo = context.TypeResolver.GetTypeInfo<T>();
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
        bool canonicalizeElements = context.TrackRef && !trackRef && elementTypeInfo.IsReferenceTrackableType;
        bool readDeclaredCompatibleTypeInfo =
            context.Compatible &&
            declared &&
            !elementTypeInfo.IsDynamicType &&
            elementTypeInfo.NeedsTypeInfoForField();

        List<T> values = new(length);
        if (!sameType)
        {
            if (trackRef)
            {
                for (int i = 0; i < length; i++)
                {
                    values.Add(elementSerializer.Read(context, RefMode.Tracking, true));
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
                        values.Add(ReadCollectionElementWithCanonicalization(elementSerializer, context, true, canonicalizeElements));
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
                    values.Add(ReadCollectionElementWithCanonicalization(elementSerializer, context, true, canonicalizeElements));
                }
            }

            return values;
        }

        if (!declared || readDeclaredCompatibleTypeInfo)
        {
            context.TypeResolver.ReadTypeInfo(elementSerializer, context);
        }

        if (trackRef)
        {
            for (int i = 0; i < length; i++)
            {
                values.Add(elementSerializer.Read(context, RefMode.Tracking, false));
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
                    values.Add(ReadCollectionElementDataWithCanonicalization(elementSerializer, context, canonicalizeElements));
                }
            }
        }
        else
        {
            for (int i = 0; i < length; i++)
            {
                values.Add(ReadCollectionElementDataWithCanonicalization(elementSerializer, context, canonicalizeElements));
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
        ReadContext context,
        bool readTypeInfo,
        bool canonicalize)
    {
        if (!canonicalize)
        {
            return elementSerializer.Read(context, RefMode.None, readTypeInfo);
        }

        int start = context.Reader.Cursor;
        T value = elementSerializer.Read(context, RefMode.None, readTypeInfo);
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }

    private static T ReadCollectionElementDataWithCanonicalization<T>(
        Serializer<T> elementSerializer,
        ReadContext context,
        bool canonicalize)
    {
        if (!canonicalize)
        {
            return elementSerializer.ReadData(context);
        }

        int start = context.Reader.Cursor;
        T value = elementSerializer.ReadData(context);
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
        if (IsListLike(value, valueType))
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

    public static bool TryWritePayload(object value, WriteContext context, bool hasGenerics)
    {
        if (value is IDictionary dictionary)
        {
            NullableKeyDictionary<object, object?> map = new();
            foreach (DictionaryEntry entry in dictionary)
            {
                map.Add(entry.Key, entry.Value);
            }

            context.TypeResolver.GetSerializer<NullableKeyDictionary<object, object?>>().WriteData(context, map, false);
            return true;
        }

        Type valueType = value.GetType();
        if (TryGetListLikeEnumerable(value, valueType, out IEnumerable? listLike, out int countHint))
        {
            List<object?> values = countHint >= 0 ? new List<object?>(countHint) : [];
            foreach (object? item in listLike!)
            {
                values.Add(item);
            }

            context.TypeResolver.GetSerializer<List<object?>>().WriteData(context, values, hasGenerics);
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

        context.TypeResolver.GetSerializer<HashSet<object?>>().WriteData(context, set, hasGenerics);
        return true;
    }

    public static List<object?> ReadListPayload(ReadContext context)
    {
        return context.TypeResolver.GetSerializer<List<object?>>().ReadData(context);
    }

    public static HashSet<object?> ReadSetPayload(ReadContext context)
    {
        return context.TypeResolver.GetSerializer<HashSet<object?>>().ReadData(context);
    }

    public static object ReadMapPayload(ReadContext context)
    {
        NullableKeyDictionary<object, object?> map = context.TypeResolver.GetSerializer<NullableKeyDictionary<object, object?>>().ReadData(context);
        if (map.HasNullKey)
        {
            return map;
        }

        return new Dictionary<object, object?>(map.NonNullEntries);
    }

    private static bool TryGetListLikeEnumerable(
        object value,
        Type valueType,
        out IEnumerable? enumerable,
        out int countHint)
    {
        if (valueType.IsArray)
        {
            enumerable = null;
            countHint = 0;
            return false;
        }

        if (value is IList list)
        {
            enumerable = list;
            countHint = list.Count;
            return true;
        }

        if (!IsListLike(value, valueType))
        {
            enumerable = null;
            countHint = 0;
            return false;
        }

        if (value is ICollection collection)
        {
            enumerable = collection;
            countHint = collection.Count;
            return true;
        }

        if (value is IEnumerable genericEnumerable)
        {
            enumerable = genericEnumerable;
            countHint = -1;
            return true;
        }

        enumerable = null;
        countHint = 0;
        return false;
    }

    private static bool IsListLike(object value, Type valueType)
    {
        if (value is IList && !valueType.IsArray)
        {
            return true;
        }

        if (!valueType.IsGenericType)
        {
            return false;
        }

        return HasGenericDefinition(valueType, static def =>
            def == typeof(LinkedList<>) ||
            def == typeof(Queue<>) ||
            def == typeof(Stack<>) ||
            def == typeof(IList<>) ||
            def == typeof(IReadOnlyList<>));
    }

    private static bool IsSet(Type valueType)
    {
        if (!valueType.IsGenericType)
        {
            return false;
        }

        return HasGenericDefinition(valueType, static def =>
            def == typeof(ISet<>) ||
            def == typeof(IReadOnlySet<>) ||
            def == typeof(IImmutableSet<>) ||
            def == typeof(HashSet<>) ||
            def == typeof(SortedSet<>) ||
            def == typeof(ImmutableHashSet<>));
    }

    private static bool HasGenericDefinition(Type valueType, Func<Type, bool> definitionPredicate)
    {
        if (valueType.IsGenericType && definitionPredicate(valueType.GetGenericTypeDefinition()))
        {
            return true;
        }

        foreach (Type iface in valueType.GetInterfaces())
        {
            if (!iface.IsGenericType)
            {
                continue;
            }

            if (definitionPredicate(iface.GetGenericTypeDefinition()))
            {
                return true;
            }
        }

        return false;
    }
}

public sealed class ArraySerializer<T> : Serializer<T[]>
{
    public override T[] DefaultValue => null!;

    public override void WriteData(WriteContext context, in T[] value, bool hasGenerics)
    {
        T[] safe = value ?? [];
        CollectionCodec.WriteCollectionData(
            safe,
            context.TypeResolver.GetSerializer<T>(),
            context,
            hasGenerics);
    }

    public override T[] ReadData(ReadContext context)
    {
        List<T> values = CollectionCodec.ReadCollectionData<T>(context.TypeResolver.GetSerializer<T>(), context);
        return values.ToArray();
    }
}

public class ListSerializer<T> : Serializer<List<T>>
{
    public override List<T> DefaultValue => null!;

    public override void WriteData(WriteContext context, in List<T> value, bool hasGenerics)
    {
        List<T> safe = value ?? [];
        CollectionCodec.WriteCollectionData(safe, context.TypeResolver.GetSerializer<T>(), context, hasGenerics);
    }

    public override List<T> ReadData(ReadContext context)
    {
        return CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), context);
    }
}

public sealed class SetSerializer<T> : Serializer<HashSet<T>> where T : notnull
{
    public override HashSet<T> DefaultValue => null!;

    public override void WriteData(WriteContext context, in HashSet<T> value, bool hasGenerics)
    {
        HashSet<T> safe = value ?? [];
        CollectionCodec.WriteCollectionData(safe, context.TypeResolver.GetSerializer<T>(), context, hasGenerics);
    }

    public override HashSet<T> ReadData(ReadContext context)
    {
        return [.. CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), context)];
    }
}

public sealed class SortedSetSerializer<T> : Serializer<SortedSet<T>> where T : notnull
{
    public override SortedSet<T> DefaultValue => null!;

    public override void WriteData(WriteContext context, in SortedSet<T> value, bool hasGenerics)
    {
        SortedSet<T> safe = value ?? new SortedSet<T>();
        CollectionCodec.WriteCollectionData(safe, context.TypeResolver.GetSerializer<T>(), context, hasGenerics);
    }

    public override SortedSet<T> ReadData(ReadContext context)
    {
        return [.. CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), context)];
    }
}

public sealed class ImmutableHashSetSerializer<T> : Serializer<ImmutableHashSet<T>> where T : notnull
{
    public override ImmutableHashSet<T> DefaultValue => null!;

    public override void WriteData(WriteContext context, in ImmutableHashSet<T> value, bool hasGenerics)
    {
        ImmutableHashSet<T> safe = value ?? ImmutableHashSet<T>.Empty;
        CollectionCodec.WriteCollectionData(safe, context.TypeResolver.GetSerializer<T>(), context, hasGenerics);
    }

    public override ImmutableHashSet<T> ReadData(ReadContext context)
    {
        return ImmutableHashSet.CreateRange(CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), context));
    }
}

public sealed class LinkedListSerializer<T> : Serializer<LinkedList<T>>
{
    public override LinkedList<T> DefaultValue => null!;

    public override void WriteData(WriteContext context, in LinkedList<T> value, bool hasGenerics)
    {
        LinkedList<T> safe = value ?? new LinkedList<T>();
        CollectionCodec.WriteCollectionData(safe, context.TypeResolver.GetSerializer<T>(), context, hasGenerics);
    }

    public override LinkedList<T> ReadData(ReadContext context)
    {
        return new LinkedList<T>(CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), context));
    }
}

public sealed class QueueSerializer<T> : Serializer<Queue<T>>
{
    public override Queue<T> DefaultValue => null!;

    public override void WriteData(WriteContext context, in Queue<T> value, bool hasGenerics)
    {
        Queue<T> safe = value ?? new Queue<T>();
        CollectionCodec.WriteCollectionData(safe, context.TypeResolver.GetSerializer<T>(), context, hasGenerics);
    }

    public override Queue<T> ReadData(ReadContext context)
    {
        List<T> values = CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), context);
        Queue<T> queue = new(values.Count);
        for (int i = 0; i < values.Count; i++)
        {
            queue.Enqueue(values[i]);
        }

        return queue;
    }
}

public sealed class StackSerializer<T> : Serializer<Stack<T>>
{
    public override Stack<T> DefaultValue => null!;

    public override void WriteData(WriteContext context, in Stack<T> value, bool hasGenerics)
    {
        Stack<T> safe = value ?? new Stack<T>();
        if (safe.Count == 0)
        {
            CollectionCodec.WriteCollectionData(Array.Empty<T>(), context.TypeResolver.GetSerializer<T>(), context, hasGenerics);
            return;
        }

        T[] topToBottom = safe.ToArray();
        List<T> bottomToTop = new(topToBottom.Length);
        for (int i = topToBottom.Length - 1; i >= 0; i--)
        {
            bottomToTop.Add(topToBottom[i]);
        }

        CollectionCodec.WriteCollectionData(bottomToTop, context.TypeResolver.GetSerializer<T>(), context, hasGenerics);
    }

    public override Stack<T> ReadData(ReadContext context)
    {
        List<T> values = CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), context);
        Stack<T> stack = new(values.Count);
        for (int i = 0; i < values.Count; i++)
        {
            stack.Push(values[i]);
        }

        return stack;
    }
}
