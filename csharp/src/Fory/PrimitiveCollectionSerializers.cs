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

using System.Collections.Immutable;

namespace Apache.Fory;

internal static class PrimitiveCollectionHeader
{
    public static void WriteListHeader(ref WriteContext context, int count, bool hasGenerics, TypeId elementTypeId, bool hasNull)
    {
        context.Writer.WriteVarUInt32((uint)count);
        if (count == 0)
        {
            return;
        }

        bool declared = hasGenerics && !elementTypeId.NeedsTypeInfoForField();
        byte header = CollectionBits.SameType;
        if (hasNull)
        {
            header |= CollectionBits.HasNull;
        }

        if (declared)
        {
            header |= CollectionBits.DeclaredElementType;
        }

        context.Writer.WriteUInt8(header);
        if (!declared)
        {
            context.Writer.WriteUInt8((byte)elementTypeId);
        }
    }
}

internal sealed class ListBoolSerializer : Serializer<List<bool>>
{
    private static readonly ListSerializer<bool> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<bool> DefaultValue => null!;

    public override bool IsNone(in List<bool> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<bool> value, bool hasGenerics)
    {
        List<bool> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Bool, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteUInt8(list[i] ? (byte)1 : (byte)0);
        }
    }

    public override List<bool> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListInt8Serializer : Serializer<List<sbyte>>
{
    private static readonly ListSerializer<sbyte> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<sbyte> DefaultValue => null!;

    public override bool IsNone(in List<sbyte> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<sbyte> value, bool hasGenerics)
    {
        List<sbyte> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Int8, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteInt8(list[i]);
        }
    }

    public override List<sbyte> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListInt16Serializer : Serializer<List<short>>
{
    private static readonly ListSerializer<short> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<short> DefaultValue => null!;

    public override bool IsNone(in List<short> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<short> value, bool hasGenerics)
    {
        List<short> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Int16, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteInt16(list[i]);
        }
    }

    public override List<short> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListIntSerializer : Serializer<List<int>>
{
    private static readonly ListSerializer<int> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<int> DefaultValue => null!;

    public override bool IsNone(in List<int> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<int> value, bool hasGenerics)
    {
        List<int> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.VarInt32, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteVarInt32(list[i]);
        }
    }

    public override List<int> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListLongSerializer : Serializer<List<long>>
{
    private static readonly ListSerializer<long> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<long> DefaultValue => null!;

    public override bool IsNone(in List<long> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<long> value, bool hasGenerics)
    {
        List<long> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.VarInt64, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteVarInt64(list[i]);
        }
    }

    public override List<long> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListUInt8Serializer : Serializer<List<byte>>
{
    private static readonly ListSerializer<byte> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<byte> DefaultValue => null!;

    public override bool IsNone(in List<byte> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<byte> value, bool hasGenerics)
    {
        List<byte> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.UInt8, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteUInt8(list[i]);
        }
    }

    public override List<byte> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListUInt16Serializer : Serializer<List<ushort>>
{
    private static readonly ListSerializer<ushort> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<ushort> DefaultValue => null!;

    public override bool IsNone(in List<ushort> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<ushort> value, bool hasGenerics)
    {
        List<ushort> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.UInt16, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteUInt16(list[i]);
        }
    }

    public override List<ushort> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListUIntSerializer : Serializer<List<uint>>
{
    private static readonly ListSerializer<uint> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<uint> DefaultValue => null!;

    public override bool IsNone(in List<uint> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<uint> value, bool hasGenerics)
    {
        List<uint> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.VarUInt32, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteVarUInt32(list[i]);
        }
    }

    public override List<uint> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListULongSerializer : Serializer<List<ulong>>
{
    private static readonly ListSerializer<ulong> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<ulong> DefaultValue => null!;

    public override bool IsNone(in List<ulong> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<ulong> value, bool hasGenerics)
    {
        List<ulong> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.VarUInt64, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteVarUInt64(list[i]);
        }
    }

    public override List<ulong> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListFloatSerializer : Serializer<List<float>>
{
    private static readonly ListSerializer<float> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<float> DefaultValue => null!;

    public override bool IsNone(in List<float> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<float> value, bool hasGenerics)
    {
        List<float> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Float32, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteFloat32(list[i]);
        }
    }

    public override List<float> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListDoubleSerializer : Serializer<List<double>>
{
    private static readonly ListSerializer<double> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<double> DefaultValue => null!;

    public override bool IsNone(in List<double> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<double> value, bool hasGenerics)
    {
        List<double> list = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.Float64, false);
        for (int i = 0; i < list.Count; i++)
        {
            context.Writer.WriteFloat64(list[i]);
        }
    }

    public override List<double> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class ListStringSerializer : Serializer<List<string>>
{
    private static readonly ListSerializer<string> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override List<string> DefaultValue => null!;

    public override bool IsNone(in List<string> value) => value is null;

    public override void WriteData(ref WriteContext context, in List<string> value, bool hasGenerics)
    {
        List<string> list = value ?? [];
        bool hasNull = false;
        for (int i = 0; i < list.Count; i++)
        {
            if (list[i] is null)
            {
                hasNull = true;
                break;
            }
        }

        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TypeId.String, hasNull);
        if (hasNull)
        {
            for (int i = 0; i < list.Count; i++)
            {
                string? item = list[i];
                if (item is null)
                {
                    context.Writer.WriteInt8((sbyte)RefFlag.Null);
                    continue;
                }

                context.Writer.WriteInt8((sbyte)RefFlag.NotNullValue);
                StringSerializer.WriteString(ref context, item);
            }

            return;
        }

        for (int i = 0; i < list.Count; i++)
        {
            StringSerializer.WriteString(ref context, list[i]);
        }
    }

    public override List<string> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetInt8Serializer : Serializer<HashSet<sbyte>>
{
    private static readonly SetSerializer<sbyte> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<sbyte> DefaultValue => null!;

    public override bool IsNone(in HashSet<sbyte> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<sbyte> value, bool hasGenerics)
    {
        HashSet<sbyte> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.Int8, false);
        foreach (sbyte item in set)
        {
            context.Writer.WriteInt8(item);
        }
    }

    public override HashSet<sbyte> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetInt16Serializer : Serializer<HashSet<short>>
{
    private static readonly SetSerializer<short> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<short> DefaultValue => null!;

    public override bool IsNone(in HashSet<short> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<short> value, bool hasGenerics)
    {
        HashSet<short> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.Int16, false);
        foreach (short item in set)
        {
            context.Writer.WriteInt16(item);
        }
    }

    public override HashSet<short> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetIntSerializer : Serializer<HashSet<int>>
{
    private static readonly SetSerializer<int> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<int> DefaultValue => null!;

    public override bool IsNone(in HashSet<int> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<int> value, bool hasGenerics)
    {
        HashSet<int> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.VarInt32, false);
        foreach (int item in set)
        {
            context.Writer.WriteVarInt32(item);
        }
    }

    public override HashSet<int> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetLongSerializer : Serializer<HashSet<long>>
{
    private static readonly SetSerializer<long> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<long> DefaultValue => null!;

    public override bool IsNone(in HashSet<long> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<long> value, bool hasGenerics)
    {
        HashSet<long> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.VarInt64, false);
        foreach (long item in set)
        {
            context.Writer.WriteVarInt64(item);
        }
    }

    public override HashSet<long> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetUInt8Serializer : Serializer<HashSet<byte>>
{
    private static readonly SetSerializer<byte> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<byte> DefaultValue => null!;

    public override bool IsNone(in HashSet<byte> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<byte> value, bool hasGenerics)
    {
        HashSet<byte> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.UInt8, false);
        foreach (byte item in set)
        {
            context.Writer.WriteUInt8(item);
        }
    }

    public override HashSet<byte> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetUInt16Serializer : Serializer<HashSet<ushort>>
{
    private static readonly SetSerializer<ushort> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<ushort> DefaultValue => null!;

    public override bool IsNone(in HashSet<ushort> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<ushort> value, bool hasGenerics)
    {
        HashSet<ushort> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.UInt16, false);
        foreach (ushort item in set)
        {
            context.Writer.WriteUInt16(item);
        }
    }

    public override HashSet<ushort> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetUIntSerializer : Serializer<HashSet<uint>>
{
    private static readonly SetSerializer<uint> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<uint> DefaultValue => null!;

    public override bool IsNone(in HashSet<uint> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<uint> value, bool hasGenerics)
    {
        HashSet<uint> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.VarUInt32, false);
        foreach (uint item in set)
        {
            context.Writer.WriteVarUInt32(item);
        }
    }

    public override HashSet<uint> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetULongSerializer : Serializer<HashSet<ulong>>
{
    private static readonly SetSerializer<ulong> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<ulong> DefaultValue => null!;

    public override bool IsNone(in HashSet<ulong> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<ulong> value, bool hasGenerics)
    {
        HashSet<ulong> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.VarUInt64, false);
        foreach (ulong item in set)
        {
            context.Writer.WriteVarUInt64(item);
        }
    }

    public override HashSet<ulong> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetFloatSerializer : Serializer<HashSet<float>>
{
    private static readonly SetSerializer<float> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<float> DefaultValue => null!;

    public override bool IsNone(in HashSet<float> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<float> value, bool hasGenerics)
    {
        HashSet<float> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.Float32, false);
        foreach (float item in set)
        {
            context.Writer.WriteFloat32(item);
        }
    }

    public override HashSet<float> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal sealed class SetDoubleSerializer : Serializer<HashSet<double>>
{
    private static readonly SetSerializer<double> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override HashSet<double> DefaultValue => null!;

    public override bool IsNone(in HashSet<double> value) => value is null;

    public override void WriteData(ref WriteContext context, in HashSet<double> value, bool hasGenerics)
    {
        HashSet<double> set = value ?? [];
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TypeId.Float64, false);
        foreach (double item in set)
        {
            context.Writer.WriteFloat64(item);
        }
    }

    public override HashSet<double> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal class PrimitiveLinkedListSerializer<T, TCodec> : Serializer<LinkedList<T>>
    where TCodec : struct, IPrimitiveDictionaryCodec<T>
{
    private static readonly LinkedListSerializer<T> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override LinkedList<T> DefaultValue => null!;

    public override bool IsNone(in LinkedList<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in LinkedList<T> value, bool hasGenerics)
    {
        if (TCodec.IsNullable)
        {
            throw new InvalidDataException("nullable primitive codecs are not supported for linked-list fast path");
        }

        LinkedList<T> list = value ?? new LinkedList<T>();
        PrimitiveCollectionHeader.WriteListHeader(ref context, list.Count, hasGenerics, TCodec.WireTypeId, false);
        foreach (T item in list)
        {
            TCodec.Write(ref context, item);
        }
    }

    public override LinkedList<T> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal class PrimitiveQueueSerializer<T, TCodec> : Serializer<Queue<T>>
    where TCodec : struct, IPrimitiveDictionaryCodec<T>
{
    private static readonly QueueSerializer<T> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Queue<T> DefaultValue => null!;

    public override bool IsNone(in Queue<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in Queue<T> value, bool hasGenerics)
    {
        if (TCodec.IsNullable)
        {
            throw new InvalidDataException("nullable primitive codecs are not supported for queue fast path");
        }

        Queue<T> queue = value ?? new Queue<T>();
        PrimitiveCollectionHeader.WriteListHeader(ref context, queue.Count, hasGenerics, TCodec.WireTypeId, false);
        foreach (T item in queue)
        {
            TCodec.Write(ref context, item);
        }
    }

    public override Queue<T> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal class PrimitiveStackSerializer<T, TCodec> : Serializer<Stack<T>>
    where TCodec : struct, IPrimitiveDictionaryCodec<T>
{
    private static readonly StackSerializer<T> Fallback = new();

    public override TypeId StaticTypeId => TypeId.List;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override Stack<T> DefaultValue => null!;

    public override bool IsNone(in Stack<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in Stack<T> value, bool hasGenerics)
    {
        if (TCodec.IsNullable)
        {
            throw new InvalidDataException("nullable primitive codecs are not supported for stack fast path");
        }

        Stack<T> stack = value ?? new Stack<T>();
        PrimitiveCollectionHeader.WriteListHeader(ref context, stack.Count, hasGenerics, TCodec.WireTypeId, false);
        if (stack.Count == 0)
        {
            return;
        }

        T[] topToBottom = stack.ToArray();
        for (int i = topToBottom.Length - 1; i >= 0; i--)
        {
            TCodec.Write(ref context, topToBottom[i]);
        }
    }

    public override Stack<T> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal class PrimitiveSortedSetSerializer<T, TCodec> : Serializer<SortedSet<T>>
    where T : notnull
    where TCodec : struct, IPrimitiveDictionaryCodec<T>
{
    private static readonly SortedSetSerializer<T> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override SortedSet<T> DefaultValue => null!;

    public override bool IsNone(in SortedSet<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in SortedSet<T> value, bool hasGenerics)
    {
        if (TCodec.IsNullable)
        {
            throw new InvalidDataException("nullable primitive codecs are not supported for sorted-set fast path");
        }

        SortedSet<T> set = value ?? new SortedSet<T>();
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TCodec.WireTypeId, false);
        foreach (T item in set)
        {
            TCodec.Write(ref context, item);
        }
    }

    public override SortedSet<T> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}

internal class PrimitiveImmutableHashSetSerializer<T, TCodec> : Serializer<ImmutableHashSet<T>>
    where T : notnull
    where TCodec : struct, IPrimitiveDictionaryCodec<T>
{
    private static readonly ImmutableHashSetSerializer<T> Fallback = new();

    public override TypeId StaticTypeId => TypeId.Set;

    public override bool IsNullableType => true;

    public override bool IsReferenceTrackableType => true;

    public override ImmutableHashSet<T> DefaultValue => null!;

    public override bool IsNone(in ImmutableHashSet<T> value) => value is null;

    public override void WriteData(ref WriteContext context, in ImmutableHashSet<T> value, bool hasGenerics)
    {
        if (TCodec.IsNullable)
        {
            throw new InvalidDataException("nullable primitive codecs are not supported for immutable-hash-set fast path");
        }

        ImmutableHashSet<T> set = value ?? ImmutableHashSet<T>.Empty;
        PrimitiveCollectionHeader.WriteListHeader(ref context, set.Count, hasGenerics, TCodec.WireTypeId, false);
        foreach (T item in set)
        {
            TCodec.Write(ref context, item);
        }
    }

    public override ImmutableHashSet<T> ReadData(ref ReadContext context)
    {
        return Fallback.ReadData(ref context);
    }
}
