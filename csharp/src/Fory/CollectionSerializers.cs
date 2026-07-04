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
using System.Runtime.CompilerServices;

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
    private const int ReferenceBytes = 4;
    // Lower-bound shallow owner costs for retained CLR collection objects. ObjectHeaderBytes is
    // the CLR object header/method-table estimate, not a Fory wire header; element storage is
    // charged separately by count at the concrete owner path.
    private static readonly int ObjectHeaderBytes = IntPtr.Size + IntPtr.Size;
    private static readonly int ArrayOwnerBytes = ObjectHeaderBytes + sizeof(int);
    private static readonly int ListOwnerBytes = ObjectHeaderBytes + ReferenceBytes + 2 * sizeof(int);
    private static readonly int HashSetOwnerBytes = ObjectHeaderBytes + 3 * ReferenceBytes + 4 * sizeof(int);
    private static readonly int SortedSetOwnerBytes = ObjectHeaderBytes + 3 * ReferenceBytes + sizeof(int);
    private static readonly int ImmutableHashSetOwnerBytes = ObjectHeaderBytes + ReferenceBytes;
    private static readonly int LinkedListOwnerBytes = ObjectHeaderBytes + 3 * ReferenceBytes + 2 * sizeof(int);
    private static readonly int QueueOwnerBytes = ObjectHeaderBytes + ReferenceBytes + 3 * sizeof(int);
    private static readonly int StackOwnerBytes = ObjectHeaderBytes + ReferenceBytes + 2 * sizeof(int);

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal static int ElementBytes<T>() => ElementStorage<T>.Bytes;

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal static void ReserveElementStorage<T>(ReadContext context, int ownerBytes, int count)
    {
        context.ReserveGraphMemory(ownerBytes + (long)count * ElementBytes<T>());
    }

    private static bool NeedsCompatibleElementTypeMeta(TypeInfo typeInfo, WriteContext context)
    {
        return context.Compatible &&
               typeInfo.UserTypeKind == UserTypeKind.Struct &&
               typeInfo.Evolving;
    }

    private static bool CanDeclareElementType<T>(TypeInfo typeInfo, WriteContext context)
    {
        if (typeInfo.IsBuiltinType)
        {
            return true;
        }

        if (NeedsCompatibleElementTypeMeta(typeInfo, context))
        {
            return false;
        }

        if (!TypeResolver.NeedToWriteTypeInfoForField(typeInfo))
        {
            return true;
        }

        return typeof(T).IsSealed;
    }

    private static bool CanDeclareRuntimeElementType<T>(
        List<T> list,
        TypeInfo typeInfo,
        WriteContext context)
    {
        if (list.Count == 0 ||
            typeInfo.IsDynamicType ||
            typeInfo.IsBuiltinType ||
            NeedsCompatibleElementTypeMeta(typeInfo, context) ||
            !TypeResolver.NeedToWriteTypeInfoForField(typeInfo) ||
            typeof(T).IsSealed)
        {
            return false;
        }

        Type declaredType = typeof(T);
        bool nullable = typeInfo.IsNullableType;
        for (int i = 0; i < list.Count; i++)
        {
            T value = list[i];
            if (nullable && value is null)
            {
                continue;
            }

            if (value is null || value.GetType() != declaredType)
            {
                return false;
            }
        }

        return true;
    }

    public static void WriteCollectionData<T>(
        IEnumerable<T> values,
        Serializer<T> elementSerializer,
        WriteContext context,
        bool hasGenerics)
    {
        TypeInfo elementTypeInfo = context.TypeResolver.GetTypeInfo<T>();
        List<T> list = values as List<T> ?? [.. values];
        int count = list.Count;
        context.Writer.WriteVarUInt32((uint)count);
        if (count == 0)
        {
            return;
        }

        bool hasNull = false;
        if (elementTypeInfo.IsNullableType)
        {
            for (int i = 0; i < count; i++)
            {
                if (list[i] is not null)
                {
                    continue;
                }

                hasNull = true;
                break;
            }
        }

        bool trackRef = context.TrackRef && elementTypeInfo.IsRefType;
        bool declaredElementType = hasGenerics &&
                                   (CanDeclareElementType<T>(elementTypeInfo, context) ||
                                    CanDeclareRuntimeElementType(list, elementTypeInfo, context));
        bool dynamicElementType = elementTypeInfo.IsDynamicType;
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
            context.TypeResolver.WriteTypeInfo(elementSerializer, context);
        }

        if (dynamicElementType)
        {
            RefMode refMode = trackRef ? RefMode.Tracking : hasNull ? RefMode.NullOnly : RefMode.None;
            for (int i = 0; i < count; i++)
            {
                elementSerializer.Write(context, list[i], refMode, true, hasGenerics);
            }

            return;
        }

        if (trackRef)
        {
            for (int i = 0; i < count; i++)
            {
                elementSerializer.Write(context, list[i], RefMode.Tracking, false, hasGenerics);
            }

            return;
        }

        if (hasNull)
        {
            for (int i = 0; i < count; i++)
            {
                T element = list[i];
                if (element is null)
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

        for (int i = 0; i < count; i++)
        {
            elementSerializer.WriteData(context, list[i], hasGenerics);
        }
    }

    private static class ElementStorage<T>
    {
        internal static readonly int Bytes = typeof(T).IsValueType ? Unsafe.SizeOf<T>() : ReferenceBytes;
    }

    private interface IValueSink<T>
    {
        void Add(T value);
    }

    private readonly struct CollectionSink<TCollection, T>(TCollection values) : IValueSink<T>
        where TCollection : ICollection<T>
    {
        public void Add(T value) => values.Add(value);
    }

    private struct ArraySink<T>(T[] values) : IValueSink<T>
    {
        private int _index;

        public void Add(T value)
        {
            values[_index] = value;
            _index++;
        }
    }

    private readonly struct QueueSink<T>(Queue<T> values) : IValueSink<T>
    {
        public void Add(T value) => values.Enqueue(value);
    }

    private readonly struct StackSink<T>(Stack<T> values) : IValueSink<T>
    {
        public void Add(T value) => values.Push(value);
    }

    private static int ReadLength<T>(ReadContext context, int ownerBytes)
    {
        int length = checked((int)context.Reader.ReadVarUInt32());
        ReserveElementStorage<T>(context, ownerBytes, length);
        return length;
    }

    private static byte ReadHeader(ReadContext context, int length)
    {
        byte header = context.Reader.ReadUInt8();
        context.Reader.CheckBound(length);
        return header;
    }

    private static void ReadElements<T, TSink>(
        Serializer<T> elementSerializer,
        ReadContext context,
        int length,
        byte header,
        TSink sink)
        where TSink : struct, IValueSink<T>
    {
        // IMPORTANT: collection readers must obey the ref/null bits written on
        // the wire, not the local generic metadata that may imply a different
        // ref policy. Shared xlang tests intentionally deserialize one ref
        // policy and then serialize another local payload. DO NOT REMOVE this comment.
        bool trackRef = (header & CollectionBits.TrackingRef) != 0;
        bool hasNull = (header & CollectionBits.HasNull) != 0;
        bool declared = (header & CollectionBits.DeclaredElementType) != 0;
        bool sameType = (header & CollectionBits.SameType) != 0;

        if (!sameType)
        {
            if (trackRef)
            {
                for (int i = 0; i < length; i++)
                {
                    sink.Add(elementSerializer.Read(context, RefMode.Tracking, true));
                }

                return;
            }

            if (hasNull)
            {
                for (int i = 0; i < length; i++)
                {
                    sbyte refFlag = context.Reader.ReadInt8();
                    if (refFlag == (sbyte)RefFlag.Null)
                    {
                        sink.Add((T)elementSerializer.DefaultObject!);
                    }
                    else if (refFlag == (sbyte)RefFlag.NotNullValue)
                    {
                        sink.Add(elementSerializer.Read(context, RefMode.None, true));
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
                    sink.Add(elementSerializer.Read(context, RefMode.None, true));
                }
            }

            return;
        }

        if (!declared)
        {
            context.TypeResolver.ReadTypeInfo(elementSerializer, context);
        }

        if (trackRef)
        {
            for (int i = 0; i < length; i++)
            {
                sink.Add(elementSerializer.Read(context, RefMode.Tracking, false));
            }

            if (!declared)
            {
                context.ClearReadTypeInfo(typeof(T));
            }

            return;
        }

        if (hasNull)
        {
            for (int i = 0; i < length; i++)
            {
                sbyte refFlag = context.Reader.ReadInt8();
                if (refFlag == (sbyte)RefFlag.Null)
                {
                    sink.Add((T)elementSerializer.DefaultObject!);
                }
                else
                {
                    sink.Add(elementSerializer.ReadData(context));
                }
            }
        }
        else
        {
            for (int i = 0; i < length; i++)
            {
                sink.Add(elementSerializer.ReadData(context));
            }
        }

        if (!declared)
        {
            context.ClearReadTypeInfo(typeof(T));
        }
    }

    public static List<T> ReadCollectionData<T>(Serializer<T> elementSerializer, ReadContext context)
    {
        return ReadCollectionData(elementSerializer, context, publishRef: false, refId: 0);
    }

    internal static List<T> ReadCollectionData<T>(Serializer<T> elementSerializer, ReadContext context, uint refId)
    {
        return ReadCollectionData(elementSerializer, context, publishRef: true, refId);
    }

    // Collection and array owners may be referenced by their own elements. When a caller has
    // reserved a ref id, publish the retained owner immediately after allocation and before
    // element reads; non-ref callers use the same loop with publishRef=false.
    private static List<T> ReadCollectionData<T>(
        Serializer<T> elementSerializer,
        ReadContext context,
        bool publishRef,
        uint refId)
    {
        int length = ReadLength<T>(context, ListOwnerBytes);
        if (length == 0)
        {
            List<T> empty = [];
            if (publishRef)
            {
                context.RefReader.StoreRefAt(refId, empty);
            }

            return empty;
        }

        byte header = ReadHeader(context, length);
        List<T> values = new(length);
        if (publishRef)
        {
            context.RefReader.StoreRefAt(refId, values);
        }

        ReadElements(elementSerializer, context, length, header, new CollectionSink<List<T>, T>(values));
        return values;
    }

    internal static HashSet<T> ReadHashSetData<T>(Serializer<T> elementSerializer, ReadContext context)
        where T : notnull
    {
        return ReadHashSetData(elementSerializer, context, publishRef: false, refId: 0);
    }

    internal static HashSet<T> ReadHashSetData<T>(Serializer<T> elementSerializer, ReadContext context, uint refId)
        where T : notnull
    {
        return ReadHashSetData(elementSerializer, context, publishRef: true, refId);
    }

    private static HashSet<T> ReadHashSetData<T>(
        Serializer<T> elementSerializer,
        ReadContext context,
        bool publishRef,
        uint refId)
        where T : notnull
    {
        int length = ReadLength<T>(context, HashSetOwnerBytes);
        if (length == 0)
        {
            HashSet<T> empty = new(length);
            if (publishRef)
            {
                context.RefReader.StoreRefAt(refId, empty);
            }

            return empty;
        }

        byte header = ReadHeader(context, length);
        HashSet<T> values = new(length);
        if (publishRef)
        {
            context.RefReader.StoreRefAt(refId, values);
        }

        ReadElements(elementSerializer, context, length, header, new CollectionSink<HashSet<T>, T>(values));
        return values;
    }

    internal static SortedSet<T> ReadSortedSetData<T>(Serializer<T> elementSerializer, ReadContext context)
        where T : notnull
    {
        return ReadSortedSetData(elementSerializer, context, publishRef: false, refId: 0);
    }

    internal static SortedSet<T> ReadSortedSetData<T>(Serializer<T> elementSerializer, ReadContext context, uint refId)
        where T : notnull
    {
        return ReadSortedSetData(elementSerializer, context, publishRef: true, refId);
    }

    private static SortedSet<T> ReadSortedSetData<T>(
        Serializer<T> elementSerializer,
        ReadContext context,
        bool publishRef,
        uint refId)
        where T : notnull
    {
        int length = ReadLength<T>(context, SortedSetOwnerBytes);
        SortedSet<T> values = new();
        if (publishRef)
        {
            context.RefReader.StoreRefAt(refId, values);
        }

        if (length == 0)
        {
            return values;
        }

        byte header = ReadHeader(context, length);
        ReadElements(elementSerializer, context, length, header, new CollectionSink<SortedSet<T>, T>(values));
        return values;
    }

    internal static ImmutableHashSet<T> ReadImmutableHashSetData<T>(
        Serializer<T> elementSerializer,
        ReadContext context)
        where T : notnull
    {
        int length = ReadLength<T>(context, ImmutableHashSetOwnerBytes);
        ImmutableHashSet<T>.Builder values = ImmutableHashSet.CreateBuilder<T>();
        if (length == 0)
        {
            return values.ToImmutable();
        }

        byte header = ReadHeader(context, length);
        ReadElements(
            elementSerializer,
            context,
            length,
            header,
            new CollectionSink<ImmutableHashSet<T>.Builder, T>(values));
        return values.ToImmutable();
    }

    internal static LinkedList<T> ReadLinkedListData<T>(Serializer<T> elementSerializer, ReadContext context)
    {
        return ReadLinkedListData(elementSerializer, context, publishRef: false, refId: 0);
    }

    internal static LinkedList<T> ReadLinkedListData<T>(Serializer<T> elementSerializer, ReadContext context, uint refId)
    {
        return ReadLinkedListData(elementSerializer, context, publishRef: true, refId);
    }

    private static LinkedList<T> ReadLinkedListData<T>(
        Serializer<T> elementSerializer,
        ReadContext context,
        bool publishRef,
        uint refId)
    {
        int length = ReadLength<T>(context, LinkedListOwnerBytes);
        LinkedList<T> values = new();
        if (publishRef)
        {
            context.RefReader.StoreRefAt(refId, values);
        }

        if (length == 0)
        {
            return values;
        }

        byte header = ReadHeader(context, length);
        ReadElements(elementSerializer, context, length, header, new CollectionSink<LinkedList<T>, T>(values));
        return values;
    }

    internal static Queue<T> ReadQueueData<T>(Serializer<T> elementSerializer, ReadContext context)
    {
        return ReadQueueData(elementSerializer, context, publishRef: false, refId: 0);
    }

    internal static Queue<T> ReadQueueData<T>(Serializer<T> elementSerializer, ReadContext context, uint refId)
    {
        return ReadQueueData(elementSerializer, context, publishRef: true, refId);
    }

    private static Queue<T> ReadQueueData<T>(
        Serializer<T> elementSerializer,
        ReadContext context,
        bool publishRef,
        uint refId)
    {
        int length = ReadLength<T>(context, QueueOwnerBytes);
        Queue<T> values = new(length);
        if (publishRef)
        {
            context.RefReader.StoreRefAt(refId, values);
        }

        if (length == 0)
        {
            return values;
        }

        byte header = ReadHeader(context, length);
        ReadElements(elementSerializer, context, length, header, new QueueSink<T>(values));
        return values;
    }

    internal static Stack<T> ReadStackData<T>(Serializer<T> elementSerializer, ReadContext context)
    {
        return ReadStackData(elementSerializer, context, publishRef: false, refId: 0);
    }

    internal static Stack<T> ReadStackData<T>(Serializer<T> elementSerializer, ReadContext context, uint refId)
    {
        return ReadStackData(elementSerializer, context, publishRef: true, refId);
    }

    private static Stack<T> ReadStackData<T>(
        Serializer<T> elementSerializer,
        ReadContext context,
        bool publishRef,
        uint refId)
    {
        int length = ReadLength<T>(context, StackOwnerBytes);
        Stack<T> values = new(length);
        if (publishRef)
        {
            context.RefReader.StoreRefAt(refId, values);
        }

        if (length == 0)
        {
            return values;
        }

        byte header = ReadHeader(context, length);
        ReadElements(elementSerializer, context, length, header, new StackSink<T>(values));
        return values;
    }

    public static T[] ReadArrayData<T>(Serializer<T> elementSerializer, ReadContext context)
    {
        return ReadArrayData(elementSerializer, context, publishRef: false, refId: 0);
    }

    internal static T[] ReadArrayData<T>(Serializer<T> elementSerializer, ReadContext context, uint refId)
    {
        return ReadArrayData(elementSerializer, context, publishRef: true, refId);
    }

    private static T[] ReadArrayData<T>(
        Serializer<T> elementSerializer,
        ReadContext context,
        bool publishRef,
        uint refId)
    {
        int length = ReadLength<T>(context, ArrayOwnerBytes);
        if (length == 0)
        {
            T[] empty = [];
            if (publishRef)
            {
                context.RefReader.StoreRefAt(refId, empty);
            }

            return empty;
        }

        byte header = ReadHeader(context, length);
        T[] values = new T[length];
        if (publishRef)
        {
            context.RefReader.StoreRefAt(refId, values);
        }

        ReadElements(elementSerializer, context, length, header, new ArraySink<T>(values));
        return values;
    }
}

internal static class DynamicContainerCodec
{
    private const int ReferenceBytes = 4;
    private static readonly int DictionaryOwnerBytes =
        IntPtr.Size + IntPtr.Size + 4 * ReferenceBytes + 4 * sizeof(int);

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
        Serializer<NullableKeyDictionary<object, object?>> serializer =
            context.TypeResolver.GetSerializer<NullableKeyDictionary<object, object?>>();
        NullableKeyDictionary<object, object?> map = serializer.ReadData(context);
        if (map.HasNullKey)
        {
            return map;
        }

        context.ReserveGraphMemory(DictionaryOwnerBytes + (long)map.Count * (ReferenceBytes + ReferenceBytes));
        return new Dictionary<object, object?>(map.NonNullEntries);
    }

    public static object ReadMapPayload(ReadContext context, uint refId)
    {
        Serializer<NullableKeyDictionary<object, object?>> serializer =
            context.TypeResolver.GetSerializer<NullableKeyDictionary<object, object?>>();
        return serializer.ReadDataWithRef(context, refId);
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
        return CollectionCodec.ReadArrayData<T>(context.TypeResolver.GetSerializer<T>(), context);
    }

    public override T[] ReadDataWithRef(ReadContext context, uint refId)
    {
        return CollectionCodec.ReadArrayData(context.TypeResolver.GetSerializer<T>(), context, refId);
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

    public override List<T> ReadDataWithRef(ReadContext context, uint refId)
    {
        return CollectionCodec.ReadCollectionData(context.TypeResolver.GetSerializer<T>(), context, refId);
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
        return CollectionCodec.ReadHashSetData(context.TypeResolver.GetSerializer<T>(), context);
    }

    public override HashSet<T> ReadDataWithRef(ReadContext context, uint refId)
    {
        return CollectionCodec.ReadHashSetData(context.TypeResolver.GetSerializer<T>(), context, refId);
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
        return CollectionCodec.ReadSortedSetData(context.TypeResolver.GetSerializer<T>(), context);
    }

    public override SortedSet<T> ReadDataWithRef(ReadContext context, uint refId)
    {
        return CollectionCodec.ReadSortedSetData(context.TypeResolver.GetSerializer<T>(), context, refId);
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
        return CollectionCodec.ReadImmutableHashSetData(context.TypeResolver.GetSerializer<T>(), context);
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
        return CollectionCodec.ReadLinkedListData(context.TypeResolver.GetSerializer<T>(), context);
    }

    public override LinkedList<T> ReadDataWithRef(ReadContext context, uint refId)
    {
        return CollectionCodec.ReadLinkedListData(context.TypeResolver.GetSerializer<T>(), context, refId);
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
        return CollectionCodec.ReadQueueData(context.TypeResolver.GetSerializer<T>(), context);
    }

    public override Queue<T> ReadDataWithRef(ReadContext context, uint refId)
    {
        return CollectionCodec.ReadQueueData(context.TypeResolver.GetSerializer<T>(), context, refId);
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
        return CollectionCodec.ReadStackData(context.TypeResolver.GetSerializer<T>(), context);
    }

    public override Stack<T> ReadDataWithRef(ReadContext context, uint refId)
    {
        return CollectionCodec.ReadStackData(context.TypeResolver.GetSerializer<T>(), context, refId);
    }
}
