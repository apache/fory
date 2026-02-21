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

internal static class DynamicAnyMapBits
{
    public const byte KeyNull = 0b0000_0010;
    public const byte DeclaredKeyType = 0b0000_0100;
    public const byte ValueNull = 0b0001_0000;
}

public readonly struct ForyAnyNullValue
{
}

public sealed class ForyAnyNullValueSerializer : Serializer<ForyAnyNullValue>
{
    public static ForyAnyNullValueSerializer Instance { get; } = new();
    public override ForyTypeId StaticTypeId => ForyTypeId.None;
    public override bool IsNullableType => true;
    public override ForyAnyNullValue DefaultValue => new();
    public override bool IsNone(ForyAnyNullValue value) => true;
    public override void WriteData(ref WriteContext context, in ForyAnyNullValue value, bool hasGenerics)
    {
        _ = context;
        _ = value;
        _ = hasGenerics;
    }

    public override ForyAnyNullValue ReadData(ref ReadContext context)
    {
        _ = context;
        return new ForyAnyNullValue();
    }
}

public sealed class DynamicAnyObjectSerializer : Serializer<object?>
{
    public static DynamicAnyObjectSerializer Instance { get; } = new();

    public override ForyTypeId StaticTypeId => ForyTypeId.Unknown;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override object? DefaultValue => new ForyAnyNullValue();
    public override bool IsNone(object? value) => value is null || value is ForyAnyNullValue;

    public override void WriteData(ref WriteContext context, in object? value, bool hasGenerics)
    {
        if (IsNone(value))
        {
            return;
        }

        DynamicAnyCodec.WriteAnyPayload(value!, ref context, hasGenerics);
    }

    public override object? ReadData(ref ReadContext context)
    {
        DynamicTypeInfo? dynamicTypeInfo = context.DynamicTypeInfo(typeof(object));
        if (dynamicTypeInfo is null)
        {
            throw new ForyInvalidDataException("dynamic Any value requires type info");
        }

        context.ClearDynamicTypeInfo(typeof(object));
        if (dynamicTypeInfo.WireTypeId == ForyTypeId.None)
        {
            return new ForyAnyNullValue();
        }

        return context.TypeResolver.ReadDynamicValue(dynamicTypeInfo, ref context);
    }

    public override void WriteTypeInfo(ref WriteContext context)
    {
        throw new ForyInvalidDataException("dynamic Any value type info is runtime-only");
    }

    public override void ReadTypeInfo(ref ReadContext context)
    {
        DynamicTypeInfo typeInfo = context.TypeResolver.ReadDynamicTypeInfo(ref context);
        context.SetDynamicTypeInfo(typeof(object), typeInfo);
    }

    public override void Write(ref WriteContext context, in object? value, RefMode refMode, bool writeTypeInfo, bool hasGenerics)
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

    public override object? Read(ref ReadContext context, RefMode refMode, bool readTypeInfo)
    {
        if (refMode != RefMode.None)
        {
            sbyte rawFlag = context.Reader.ReadInt8();
            RefFlag flag = (RefFlag)rawFlag;
            switch (flag)
            {
                case RefFlag.Null:
                    return new ForyAnyNullValue();
                case RefFlag.Ref:
                {
                    uint refId = context.Reader.ReadVarUInt32();
                    object? referenced = context.RefReader.ReadRefValue(refId);
                    return referenced ?? new ForyAnyNullValue();
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

        return ReadData(ref context);
    }

    private static bool AnyValueIsReferenceTrackable(object value)
    {
        ISerializer serializer = SerializerRegistry.Get(value.GetType());
        return serializer.IsReferenceTrackableType;
    }

}

public static class DynamicAnyCodec
{
    internal static void WriteAnyTypeInfo(object value, ref WriteContext context)
    {
        if (value is ForyAnyNullValue)
        {
            context.Writer.WriteUInt8((byte)ForyTypeId.None);
            return;
        }

        if (value is IList<object?>)
        {
            context.Writer.WriteUInt8((byte)ForyTypeId.List);
            return;
        }

        if (value is IDictionary<string, object?> || value is IDictionary<int, object?>)
        {
            context.Writer.WriteUInt8((byte)ForyTypeId.Map);
            return;
        }

        ISerializer serializer = SerializerRegistry.Get(value.GetType());
        serializer.WriteTypeInfo(ref context);
    }

    public static object? CastAnyDynamicValue(object? value, Type targetType)
    {
        if (value is null || value is ForyAnyNullValue)
        {
            if (targetType == typeof(object) || targetType == typeof(ForyAnyNullValue))
            {
                return new ForyAnyNullValue();
            }

            if (!targetType.IsValueType || Nullable.GetUnderlyingType(targetType) is not null)
            {
                return null;
            }
        }

        if (value is null)
        {
            return null;
        }

        if (targetType.IsInstanceOfType(value))
        {
            return value;
        }

        throw new ForyInvalidDataException($"cannot cast dynamic Any value to {targetType}");
    }

    public static void WriteAny(ref WriteContext context, object? value, RefMode refMode, bool writeTypeInfo = true, bool hasGenerics = false)
    {
        DynamicAnyObjectSerializer.Instance.Write(ref context, value, refMode, writeTypeInfo, hasGenerics);
    }

    public static object? ReadAny(ref ReadContext context, RefMode refMode, bool readTypeInfo = true)
    {
        object? value = DynamicAnyObjectSerializer.Instance.Read(ref context, refMode, readTypeInfo);
        return value is ForyAnyNullValue ? null : value;
    }

    public static void WriteAnyList(ref WriteContext context, IList<object?>? value, RefMode refMode, bool writeTypeInfo = false, bool hasGenerics = true)
    {
        List<object?>? wrapped = value is null ? null : [.. value];
        Serializer<List<object?>> serializer = new ListSerializer<object?>();
        serializer.Write(ref context, wrapped, refMode, writeTypeInfo, hasGenerics);
    }

    public static List<object?>? ReadAnyList(ref ReadContext context, RefMode refMode, bool readTypeInfo = false)
    {
        Serializer<List<object?>> serializer = new ListSerializer<object?>();
        List<object?>? wrapped = serializer.Read(ref context, refMode, readTypeInfo);
        if (wrapped is null)
        {
            return null;
        }

        for (int i = 0; i < wrapped.Count; i++)
        {
            if (wrapped[i] is ForyAnyNullValue)
            {
                wrapped[i] = null;
            }
        }

        return wrapped;
    }

    public static void WriteStringAnyMap(ref WriteContext context, IDictionary<string, object?>? value, RefMode refMode, bool writeTypeInfo = false, bool hasGenerics = true)
    {
        Dictionary<string, object?>? wrapped = value is null ? null : new Dictionary<string, object?>(value);
        Serializer<Dictionary<string, object?>> serializer = new MapSerializer<string, object?>();
        serializer.Write(ref context, wrapped, refMode, writeTypeInfo, hasGenerics);
    }

    public static Dictionary<string, object?>? ReadStringAnyMap(ref ReadContext context, RefMode refMode, bool readTypeInfo = false)
    {
        Serializer<Dictionary<string, object?>> serializer = new MapSerializer<string, object?>();
        Dictionary<string, object?>? wrapped = serializer.Read(ref context, refMode, readTypeInfo);
        if (wrapped is null)
        {
            return null;
        }

        Dictionary<string, object?> normalized = new(wrapped.Count);
        foreach ((string key, object? value) in wrapped)
        {
            normalized[key] = value is ForyAnyNullValue ? null : value;
        }

        return normalized;
    }

    public static void WriteInt32AnyMap(ref WriteContext context, IDictionary<int, object?>? value, RefMode refMode, bool writeTypeInfo = false, bool hasGenerics = true)
    {
        Dictionary<int, object?>? wrapped = value is null ? null : new Dictionary<int, object?>(value);
        Serializer<Dictionary<int, object?>> serializer = new MapSerializer<int, object?>();
        serializer.Write(ref context, wrapped, refMode, writeTypeInfo, hasGenerics);
    }

    public static Dictionary<int, object?>? ReadInt32AnyMap(ref ReadContext context, RefMode refMode, bool readTypeInfo = false)
    {
        Serializer<Dictionary<int, object?>> serializer = new MapSerializer<int, object?>();
        Dictionary<int, object?>? wrapped = serializer.Read(ref context, refMode, readTypeInfo);
        if (wrapped is null)
        {
            return null;
        }

        Dictionary<int, object?> normalized = new(wrapped.Count);
        foreach ((int key, object? value) in wrapped)
        {
            normalized[key] = value is ForyAnyNullValue ? null : value;
        }

        return normalized;
    }

    public static object ReadDynamicAnyMapValue(ref ReadContext context)
    {
        int mapStart = context.Reader.Cursor;
        ForyTypeId? keyTypeId = PeekDynamicMapKeyTypeId(ref context);
        context.Reader.SetCursor(mapStart);
        return keyTypeId switch
        {
            ForyTypeId.Int32 or ForyTypeId.VarInt32 => ReadInt32AnyMap(ref context, RefMode.None, false) ?? new Dictionary<int, object?>(),
            ForyTypeId.String or null => ReadStringAnyMap(ref context, RefMode.None, false) ?? new Dictionary<string, object?>(),
            _ => throw new ForyInvalidDataException($"unsupported dynamic map key type {keyTypeId}"),
        };
    }

    private static ForyTypeId? PeekDynamicMapKeyTypeId(ref ReadContext context)
    {
        int start = context.Reader.Cursor;
        try
        {
            int length = checked((int)context.Reader.ReadVarUInt32());
            if (length == 0)
            {
                return null;
            }

            byte header = context.Reader.ReadUInt8();
            bool keyNull = (header & DynamicAnyMapBits.KeyNull) != 0;
            bool keyDeclared = (header & DynamicAnyMapBits.DeclaredKeyType) != 0;
            bool valueNull = (header & DynamicAnyMapBits.ValueNull) != 0;
            if (keyDeclared || keyNull)
            {
                return null;
            }

            if (!valueNull)
            {
                _ = context.Reader.ReadUInt8();
            }

            uint rawTypeId = context.Reader.ReadVarUInt32();
            return Enum.IsDefined(typeof(ForyTypeId), rawTypeId) ? (ForyTypeId)rawTypeId : null;
        }
        finally
        {
            context.Reader.SetCursor(start);
        }
    }

    public static void WriteAnyPayload(object value, ref WriteContext context, bool hasGenerics)
    {
        if (value is IList<object?> list)
        {
            WriteAnyList(ref context, list, RefMode.None, false, hasGenerics);
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

        ISerializer serializer = SerializerRegistry.Get(value.GetType());
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
                    values.Add((T)elementSerializer.Read(ref context, RefMode.Tracking, true)!);
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
                values.Add((T)elementSerializer.Read(ref context, RefMode.Tracking, false)!);
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
            return (T)elementSerializer.Read(ref context, RefMode.None, readTypeInfo)!;
        }

        int start = context.Reader.Cursor;
        T value = (T)elementSerializer.Read(ref context, RefMode.None, readTypeInfo)!;
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
