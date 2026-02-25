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

using System.Collections.Concurrent;

namespace Apache.Fory;

internal static class DictionaryBits
{
    public const byte TrackingKeyRef = 0b0000_0001;
    public const byte KeyNull = 0b0000_0010;
    public const byte DeclaredKeyType = 0b0000_0100;
    public const byte TrackingValueRef = 0b0000_1000;
    public const byte ValueNull = 0b0001_0000;
    public const byte DeclaredValueType = 0b0010_0000;
}

public abstract class DictionaryLikeSerializer<TDictionary, TKey, TValue> : Serializer<TDictionary>
    where TDictionary : class, IDictionary<TKey, TValue>
    where TKey : notnull
{
    public override TDictionary DefaultValue => null!;

    protected abstract TDictionary CreateMap(int capacity);

    protected virtual KeyValuePair<TKey, TValue>[] SnapshotPairs(TDictionary map)
    {
        return [.. map];
    }

    protected virtual void SetValue(TDictionary map, TKey key, TValue value)
    {
        map[key] = value;
    }

    public override void WriteData(WriteContext context, in TDictionary value, bool hasGenerics)
    {
        Serializer<TKey> keySerializer = context.TypeResolver.GetSerializer<TKey>();
        Serializer<TValue> valueSerializer = context.TypeResolver.GetSerializer<TValue>();
        TypeInfo keyTypeInfo = context.TypeResolver.GetTypeInfo<TKey>();
        TypeInfo valueTypeInfo = context.TypeResolver.GetTypeInfo<TValue>();
        TDictionary map = value ?? CreateMap(0);
        context.Writer.WriteVarUInt32((uint)map.Count);
        if (map.Count == 0)
        {
            return;
        }

        bool trackKeyRef = context.TrackRef && keyTypeInfo.IsReferenceTrackableType;
        bool trackValueRef = context.TrackRef && valueTypeInfo.IsReferenceTrackableType;
        bool keyDeclared = hasGenerics && !keyTypeInfo.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !valueTypeInfo.NeedsTypeInfoForField();
        bool keyDynamicType = keyTypeInfo.IsDynamicType;
        bool valueDynamicType = valueTypeInfo.IsDynamicType;
        bool writeDeclaredCompatibleKeyTypeInfo =
            context.Compatible &&
            keyDeclared &&
            !keyDynamicType &&
            keyTypeInfo.NeedsTypeInfoForField();
        bool writeDeclaredCompatibleValueTypeInfo =
            context.Compatible &&
            valueDeclared &&
            !valueDynamicType &&
            valueTypeInfo.NeedsTypeInfoForField();

        KeyValuePair<TKey, TValue>[] pairs = SnapshotPairs(map);
        if (keyDynamicType || valueDynamicType)
        {
            WriteDynamicMapPairs(
                pairs,
                context,
                hasGenerics,
                trackKeyRef,
                trackValueRef,
                keyDeclared,
                valueDeclared,
                keyDynamicType,
                valueDynamicType,
                writeDeclaredCompatibleKeyTypeInfo,
                writeDeclaredCompatibleValueTypeInfo,
                keyTypeInfo,
                valueTypeInfo,
                keySerializer,
                valueSerializer);
            return;
        }

        int index = 0;
        while (index < pairs.Length)
        {
            KeyValuePair<TKey, TValue> pair = pairs[index];
            bool keyIsNull = context.TypeResolver.IsNoneObject(keyTypeInfo, pair.Key);
            bool valueIsNull = context.TypeResolver.IsNoneObject(valueTypeInfo, pair.Value);
            if (keyIsNull || valueIsNull)
            {
                byte header = 0;
                if (trackKeyRef)
                {
                    header |= DictionaryBits.TrackingKeyRef;
                }

                if (trackValueRef)
                {
                    header |= DictionaryBits.TrackingValueRef;
                }

                if (keyIsNull)
                {
                    header |= DictionaryBits.KeyNull;
                }

                if (valueIsNull)
                {
                    header |= DictionaryBits.ValueNull;
                }

                if (!keyIsNull && keyDeclared)
                {
                    header |= DictionaryBits.DeclaredKeyType;
                }

                if (!valueIsNull && valueDeclared)
                {
                    header |= DictionaryBits.DeclaredValueType;
                }

                context.Writer.WriteUInt8(header);
                if (!keyIsNull)
                {
                    if (!keyDeclared || writeDeclaredCompatibleKeyTypeInfo)
                    {
                        context.TypeResolver.WriteTypeInfo(keySerializer, context);
                    }

                    keySerializer.Write(context, pair.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                }

                if (!valueIsNull)
                {
                    if (!valueDeclared || writeDeclaredCompatibleValueTypeInfo)
                    {
                        context.TypeResolver.WriteTypeInfo(valueSerializer, context);
                    }

                    valueSerializer.Write(context, pair.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                }

                index += 1;
                continue;
            }

            byte blockHeader = 0;
            if (trackKeyRef)
            {
                blockHeader |= DictionaryBits.TrackingKeyRef;
            }

            if (trackValueRef)
            {
                blockHeader |= DictionaryBits.TrackingValueRef;
            }

            if (keyDeclared)
            {
                blockHeader |= DictionaryBits.DeclaredKeyType;
            }

            if (valueDeclared)
            {
                blockHeader |= DictionaryBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(blockHeader);
            int chunkSizeOffset = context.Writer.Count;
            context.Writer.WriteUInt8(0);
            if (!keyDeclared || writeDeclaredCompatibleKeyTypeInfo)
            {
                context.TypeResolver.WriteTypeInfo(keySerializer, context);
            }

            if (!valueDeclared || writeDeclaredCompatibleValueTypeInfo)
            {
                context.TypeResolver.WriteTypeInfo(valueSerializer, context);
            }

            byte chunkSize = 0;
            while (index < pairs.Length && chunkSize < byte.MaxValue)
            {
                KeyValuePair<TKey, TValue> current = pairs[index];
                if (context.TypeResolver.IsNoneObject(keyTypeInfo, current.Key) ||
                    context.TypeResolver.IsNoneObject(valueTypeInfo, current.Value))
                {
                    break;
                }

                keySerializer.Write(context, current.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                valueSerializer.Write(context, current.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                chunkSize += 1;
                index += 1;
            }

            context.Writer.SetByte(chunkSizeOffset, chunkSize);
        }
    }

    public override TDictionary ReadData(ReadContext context)
    {
        Serializer<TKey> keySerializer = context.TypeResolver.GetSerializer<TKey>();
        Serializer<TValue> valueSerializer = context.TypeResolver.GetSerializer<TValue>();
        TypeInfo keyTypeInfo = context.TypeResolver.GetTypeInfo<TKey>();
        TypeInfo valueTypeInfo = context.TypeResolver.GetTypeInfo<TValue>();
        int totalLength = checked((int)context.Reader.ReadVarUInt32());
        if (totalLength == 0)
        {
            return CreateMap(0);
        }

        TDictionary map = CreateMap(totalLength);
        bool keyDynamicType = keyTypeInfo.IsDynamicType;
        bool valueDynamicType = valueTypeInfo.IsDynamicType;
        bool canonicalizeValues = context.TrackRef && valueTypeInfo.IsReferenceTrackableType;

        int readCount = 0;
        while (readCount < totalLength)
        {
            byte header = context.Reader.ReadUInt8();
            bool trackKeyRef = (header & DictionaryBits.TrackingKeyRef) != 0;
            bool keyNull = (header & DictionaryBits.KeyNull) != 0;
            bool keyDeclared = (header & DictionaryBits.DeclaredKeyType) != 0;
            bool trackValueRef = (header & DictionaryBits.TrackingValueRef) != 0;
            bool valueNull = (header & DictionaryBits.ValueNull) != 0;
            bool valueDeclared = (header & DictionaryBits.DeclaredValueType) != 0;
            bool readDeclaredCompatibleKeyTypeInfo =
                context.Compatible &&
                keyDeclared &&
                !keyDynamicType &&
                keyTypeInfo.NeedsTypeInfoForField();
            bool readDeclaredCompatibleValueTypeInfo =
                context.Compatible &&
                valueDeclared &&
                !valueDynamicType &&
                valueTypeInfo.NeedsTypeInfoForField();

            if (keyNull && valueNull)
            {
                // Dictionary-like containers cannot represent a null key.
                // Drop this entry instead of mapping it to default(TKey), which would corrupt key semantics.
                readCount += 1;
                continue;
            }

            if (keyNull)
            {
                _ = ReadValueElement(
                    context,
                    trackValueRef,
                    !valueDeclared || readDeclaredCompatibleValueTypeInfo,
                    canonicalizeValues,
                    valueSerializer);

                // Preserve stream/reference state by reading value payload, then skip null-key entry.
                readCount += 1;
                continue;
            }

            if (valueNull)
            {
                TKey key = keySerializer.Read(
                    context,
                    trackKeyRef ? RefMode.Tracking : RefMode.None,
                    !keyDeclared || readDeclaredCompatibleKeyTypeInfo);

                SetValue(map, key, (TValue)valueSerializer.DefaultObject!);
                readCount += 1;
                continue;
            }

            int chunkSize = context.Reader.ReadUInt8();
            if (keyDynamicType || valueDynamicType)
            {
                for (int i = 0; i < chunkSize; i++)
                {
                    DynamicTypeInfo? keyDynamicInfo = null;
                    DynamicTypeInfo? valueDynamicInfo = null;

                    if (!keyDeclared || readDeclaredCompatibleKeyTypeInfo)
                    {
                        if (keyDynamicType)
                        {
                            keyDynamicInfo = context.TypeResolver.ReadDynamicTypeInfo(context);
                        }
                        else
                        {
                            context.TypeResolver.ReadTypeInfo(keySerializer, context);
                        }
                    }

                    if (!valueDeclared || readDeclaredCompatibleValueTypeInfo)
                    {
                        if (valueDynamicType)
                        {
                            valueDynamicInfo = context.TypeResolver.ReadDynamicTypeInfo(context);
                        }
                        else
                        {
                            context.TypeResolver.ReadTypeInfo(valueSerializer, context);
                        }
                    }

                    if (keyDynamicInfo is not null)
                    {
                        context.SetDynamicTypeInfo(typeof(TKey), keyDynamicInfo);
                    }

                    TKey key = keySerializer.Read(context, trackKeyRef ? RefMode.Tracking : RefMode.None, false);
                    if (keyDynamicInfo is not null)
                    {
                        context.ClearDynamicTypeInfo(typeof(TKey));
                    }

                    if (valueDynamicInfo is not null)
                    {
                        context.SetDynamicTypeInfo(typeof(TValue), valueDynamicInfo);
                    }

                    TValue value = ReadValueElement(
                        context,
                        trackValueRef,
                        false,
                        canonicalizeValues,
                        valueSerializer);
                    if (valueDynamicInfo is not null)
                    {
                        context.ClearDynamicTypeInfo(typeof(TValue));
                    }

                    SetValue(map, key, value);
                }

                readCount += chunkSize;
                continue;
            }

            if (!keyDeclared || readDeclaredCompatibleKeyTypeInfo)
            {
                context.TypeResolver.ReadTypeInfo(keySerializer, context);
            }

            if (!valueDeclared || readDeclaredCompatibleValueTypeInfo)
            {
                context.TypeResolver.ReadTypeInfo(valueSerializer, context);
            }

            for (int i = 0; i < chunkSize; i++)
            {
                TKey key = keySerializer.Read(context, trackKeyRef ? RefMode.Tracking : RefMode.None, false);
                TValue value = ReadValueElement(context, trackValueRef, false, canonicalizeValues, valueSerializer);
                SetValue(map, key, value);
            }

            if (!keyDeclared)
            {
                context.ClearDynamicTypeInfo(typeof(TKey));
            }

            if (!valueDeclared)
            {
                context.ClearDynamicTypeInfo(typeof(TValue));
            }

            readCount += chunkSize;
        }

        return map;
    }

    private static void WriteDynamicMapPairs(
        KeyValuePair<TKey, TValue>[] pairs,
        WriteContext context,
        bool hasGenerics,
        bool trackKeyRef,
        bool trackValueRef,
        bool keyDeclared,
        bool valueDeclared,
        bool keyDynamicType,
        bool valueDynamicType,
        bool writeDeclaredCompatibleKeyTypeInfo,
        bool writeDeclaredCompatibleValueTypeInfo,
        TypeInfo keyTypeInfo,
        TypeInfo valueTypeInfo,
        Serializer<TKey> keySerializer,
        Serializer<TValue> valueSerializer)
    {
        foreach (KeyValuePair<TKey, TValue> pair in pairs)
        {
            bool keyIsNull = context.TypeResolver.IsNoneObject(keyTypeInfo, pair.Key);
            bool valueIsNull = context.TypeResolver.IsNoneObject(valueTypeInfo, pair.Value);
            byte header = 0;
            if (trackKeyRef)
            {
                header |= DictionaryBits.TrackingKeyRef;
            }

            if (trackValueRef)
            {
                header |= DictionaryBits.TrackingValueRef;
            }

            if (keyIsNull)
            {
                header |= DictionaryBits.KeyNull;
            }
            else if (!keyDynamicType && keyDeclared)
            {
                header |= DictionaryBits.DeclaredKeyType;
            }

            if (valueIsNull)
            {
                header |= DictionaryBits.ValueNull;
            }
            else if (!valueDynamicType && valueDeclared)
            {
                header |= DictionaryBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(header);
            if (keyIsNull && valueIsNull)
            {
                continue;
            }

            if (keyIsNull)
            {
                valueSerializer.Write(
                    context,
                    pair.Value,
                    trackValueRef ? RefMode.Tracking : RefMode.None,
                    !valueDeclared,
                    hasGenerics);
                continue;
            }

            if (valueIsNull)
            {
                keySerializer.Write(
                    context,
                    pair.Key,
                    trackKeyRef ? RefMode.Tracking : RefMode.None,
                    !keyDeclared,
                    hasGenerics);
                continue;
            }

            context.Writer.WriteUInt8(1);
            if (!keyDeclared || writeDeclaredCompatibleKeyTypeInfo)
            {
                if (keyDynamicType)
                {
                    DynamicAnyCodec.WriteAnyTypeInfo(pair.Key!, context);
                }
                else
                {
                    context.TypeResolver.WriteTypeInfo(keySerializer, context);
                }
            }

            if (!valueDeclared || writeDeclaredCompatibleValueTypeInfo)
            {
                if (valueDynamicType)
                {
                    DynamicAnyCodec.WriteAnyTypeInfo(pair.Value!, context);
                }
                else
                {
                    context.TypeResolver.WriteTypeInfo(valueSerializer, context);
                }
            }

            keySerializer.Write(context, pair.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
            valueSerializer.Write(context, pair.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
        }
    }

    private static TValue ReadValueElement(
        ReadContext context,
        bool trackValueRef,
        bool readTypeInfo,
        bool canonicalizeValues,
        Serializer<TValue> valueSerializer)
    {
        if (trackValueRef || !canonicalizeValues)
        {
            return valueSerializer.Read(context, trackValueRef ? RefMode.Tracking : RefMode.None, readTypeInfo);
        }

        int start = context.Reader.Cursor;
        TValue value = valueSerializer.Read(context, RefMode.None, readTypeInfo);
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }
}

public class DictionarySerializer<TKey, TValue> : DictionaryLikeSerializer<Dictionary<TKey, TValue>, TKey, TValue>
    where TKey : notnull
{
    protected override Dictionary<TKey, TValue> CreateMap(int capacity)
    {
        return new Dictionary<TKey, TValue>(capacity);
    }
}

public class SortedDictionarySerializer<TKey, TValue> : DictionaryLikeSerializer<SortedDictionary<TKey, TValue>, TKey, TValue>
    where TKey : notnull
{
    protected override SortedDictionary<TKey, TValue> CreateMap(int capacity)
    {
        _ = capacity;
        return new SortedDictionary<TKey, TValue>();
    }
}

public class SortedListSerializer<TKey, TValue> : DictionaryLikeSerializer<SortedList<TKey, TValue>, TKey, TValue>
    where TKey : notnull
{
    protected override SortedList<TKey, TValue> CreateMap(int capacity)
    {
        return new SortedList<TKey, TValue>(capacity);
    }
}

public class ConcurrentDictionarySerializer<TKey, TValue> : DictionaryLikeSerializer<ConcurrentDictionary<TKey, TValue>, TKey, TValue>
    where TKey : notnull
{
    protected override ConcurrentDictionary<TKey, TValue> CreateMap(int capacity)
    {
        int initialCapacity = Math.Max(capacity, 1);
        return new ConcurrentDictionary<TKey, TValue>(Environment.ProcessorCount, initialCapacity);
    }

    protected override KeyValuePair<TKey, TValue>[] SnapshotPairs(ConcurrentDictionary<TKey, TValue> map)
    {
        return map.ToArray();
    }
}
