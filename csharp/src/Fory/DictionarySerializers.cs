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

internal static class DictionaryBits
{
    public const byte TrackingKeyRef = 0b0000_0001;
    public const byte KeyNull = 0b0000_0010;
    public const byte DeclaredKeyType = 0b0000_0100;
    public const byte TrackingValueRef = 0b0000_1000;
    public const byte ValueNull = 0b0001_0000;
    public const byte DeclaredValueType = 0b0010_0000;
}

public class DictionarySerializer<TKey, TValue> : TypedSerializer<Dictionary<TKey, TValue>>
    where TKey : notnull
{
    public override TypeId StaticTypeId => TypeId.Map;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override Dictionary<TKey, TValue> DefaultValue => null!;
    public override bool IsNone(in Dictionary<TKey, TValue> value) => value is null;

    public override void WriteData(ref WriteContext context, in Dictionary<TKey, TValue> value, bool hasGenerics)
    {
        Serializer<TKey> keySerializer = context.TypeResolver.GetSerializer<TKey>();
        Serializer<TValue> valueSerializer = context.TypeResolver.GetSerializer<TValue>();
        Dictionary<TKey, TValue> map = value ?? [];
        context.Writer.WriteVarUInt32((uint)map.Count);
        if (map.Count == 0)
        {
            return;
        }

        bool trackKeyRef = context.TrackRef && keySerializer.IsReferenceTrackableType;
        bool trackValueRef = context.TrackRef && valueSerializer.IsReferenceTrackableType;
        bool keyDeclared = hasGenerics && !keySerializer.StaticTypeId.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !valueSerializer.StaticTypeId.NeedsTypeInfoForField();
        bool keyDynamicType = keySerializer.StaticTypeId == TypeId.Unknown;
        bool valueDynamicType = valueSerializer.StaticTypeId == TypeId.Unknown;

        KeyValuePair<TKey, TValue>[] pairs = [.. map];
        if (keyDynamicType || valueDynamicType)
        {
            WriteDynamicMapPairs(
                pairs,
                ref context,
                hasGenerics,
                trackKeyRef,
                trackValueRef,
                keyDeclared,
                valueDeclared,
                keyDynamicType,
                valueDynamicType,
                keySerializer,
                valueSerializer);
            return;
        }

        int index = 0;
        while (index < pairs.Length)
        {
            KeyValuePair<TKey, TValue> pair = pairs[index];
            bool keyIsNull = keySerializer.IsNoneObject(pair.Key);
            bool valueIsNull = valueSerializer.IsNoneObject(pair.Value);
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
                    if (!keyDeclared)
                    {
                        keySerializer.WriteTypeInfo(ref context);
                    }

                    keySerializer.Write(ref context, pair.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                }

                if (!valueIsNull)
                {
                    if (!valueDeclared)
                    {
                        valueSerializer.WriteTypeInfo(ref context);
                    }

                    valueSerializer.Write(ref context, pair.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
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
            if (!keyDeclared)
            {
                keySerializer.WriteTypeInfo(ref context);
            }

            if (!valueDeclared)
            {
                valueSerializer.WriteTypeInfo(ref context);
            }

            byte chunkSize = 0;
            while (index < pairs.Length && chunkSize < byte.MaxValue)
            {
                KeyValuePair<TKey, TValue> current = pairs[index];
                if (keySerializer.IsNoneObject(current.Key) || valueSerializer.IsNoneObject(current.Value))
                {
                    break;
                }

                keySerializer.Write(ref context, current.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                valueSerializer.Write(ref context, current.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
                chunkSize += 1;
                index += 1;
            }

            context.Writer.SetByte(chunkSizeOffset, chunkSize);
        }
    }

    public override Dictionary<TKey, TValue> ReadData(ref ReadContext context)
    {
        Serializer<TKey> keySerializer = context.TypeResolver.GetSerializer<TKey>();
        Serializer<TValue> valueSerializer = context.TypeResolver.GetSerializer<TValue>();
        int totalLength = checked((int)context.Reader.ReadVarUInt32());
        if (totalLength == 0)
        {
            return [];
        }

        Dictionary<TKey, TValue> map = new(totalLength);
        bool keyDynamicType = keySerializer.StaticTypeId == TypeId.Unknown;
        bool valueDynamicType = valueSerializer.StaticTypeId == TypeId.Unknown;
        bool canonicalizeValues = context.TrackRef && valueSerializer.IsReferenceTrackableType;

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

            if (keyNull && valueNull)
            {
                // Dictionary<TKey, TValue> cannot represent a null key.
                // Drop this entry instead of mapping it to default(TKey), which would corrupt key semantics.
                readCount += 1;
                continue;
            }

            if (keyNull)
            {
                TValue value = ReadValueElement(
                    ref context,
                    trackValueRef,
                    !valueDeclared,
                    canonicalizeValues,
                    valueSerializer);

                // Preserve stream/reference state by reading value payload, then skip null-key entry.
                // This avoids injecting a fake default(TKey) key into Dictionary.
                readCount += 1;
                continue;
            }

            if (valueNull)
            {
                TKey key = keySerializer.Read(
                    ref context,
                    trackKeyRef ? RefMode.Tracking : RefMode.None,
                    !keyDeclared);

                map[key] = (TValue)valueSerializer.DefaultObject!;
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

                    if (!keyDeclared)
                    {
                        if (keyDynamicType)
                        {
                            keyDynamicInfo = context.TypeResolver.ReadDynamicTypeInfo(ref context);
                        }
                        else
                        {
                            keySerializer.ReadTypeInfo(ref context);
                        }
                    }

                    if (!valueDeclared)
                    {
                        if (valueDynamicType)
                        {
                            valueDynamicInfo = context.TypeResolver.ReadDynamicTypeInfo(ref context);
                        }
                        else
                        {
                            valueSerializer.ReadTypeInfo(ref context);
                        }
                    }

                    if (keyDynamicInfo is not null)
                    {
                        context.SetDynamicTypeInfo(typeof(TKey), keyDynamicInfo);
                    }

                    TKey key = keySerializer.Read(ref context, trackKeyRef ? RefMode.Tracking : RefMode.None, false);
                    if (keyDynamicInfo is not null)
                    {
                        context.ClearDynamicTypeInfo(typeof(TKey));
                    }

                    if (valueDynamicInfo is not null)
                    {
                        context.SetDynamicTypeInfo(typeof(TValue), valueDynamicInfo);
                    }

                    TValue value = ReadValueElement(
                        ref context,
                        trackValueRef,
                        false,
                        canonicalizeValues,
                        valueSerializer);
                    if (valueDynamicInfo is not null)
                    {
                        context.ClearDynamicTypeInfo(typeof(TValue));
                    }

                    map[key] = value;
                }

                readCount += chunkSize;
                continue;
            }

            if (!keyDeclared)
            {
                keySerializer.ReadTypeInfo(ref context);
            }

            if (!valueDeclared)
            {
                valueSerializer.ReadTypeInfo(ref context);
            }

            for (int i = 0; i < chunkSize; i++)
            {
                TKey key = keySerializer.Read(ref context, trackKeyRef ? RefMode.Tracking : RefMode.None, false);
                TValue value = ReadValueElement(ref context, trackValueRef, false, canonicalizeValues, valueSerializer);
                map[key] = value;
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
        ref WriteContext context,
        bool hasGenerics,
        bool trackKeyRef,
        bool trackValueRef,
        bool keyDeclared,
        bool valueDeclared,
        bool keyDynamicType,
        bool valueDynamicType,
        Serializer<TKey> keySerializer,
        Serializer<TValue> valueSerializer)
    {
        foreach (KeyValuePair<TKey, TValue> pair in pairs)
        {
            bool keyIsNull = keySerializer.IsNoneObject(pair.Key);
            bool valueIsNull = valueSerializer.IsNoneObject(pair.Value);
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
                    ref context,
                    pair.Value,
                    trackValueRef ? RefMode.Tracking : RefMode.None,
                    !valueDeclared,
                    hasGenerics);
                continue;
            }

            if (valueIsNull)
            {
                keySerializer.Write(
                    ref context,
                    pair.Key,
                    trackKeyRef ? RefMode.Tracking : RefMode.None,
                    !keyDeclared,
                    hasGenerics);
                continue;
            }

            context.Writer.WriteUInt8(1);
            if (!keyDeclared)
            {
                if (keyDynamicType)
                {
                    DynamicAnyCodec.WriteAnyTypeInfo(pair.Key!, ref context);
                }
                else
                {
                    keySerializer.WriteTypeInfo(ref context);
                }
            }

            if (!valueDeclared)
            {
                if (valueDynamicType)
                {
                    DynamicAnyCodec.WriteAnyTypeInfo(pair.Value!, ref context);
                }
                else
                {
                    valueSerializer.WriteTypeInfo(ref context);
                }
            }

            keySerializer.Write(ref context, pair.Key, trackKeyRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
            valueSerializer.Write(ref context, pair.Value, trackValueRef ? RefMode.Tracking : RefMode.None, false, hasGenerics);
        }
    }

    private static TValue ReadValueElement(
        ref ReadContext context,
        bool trackValueRef,
        bool readTypeInfo,
        bool canonicalizeValues,
        Serializer<TValue> valueSerializer)
    {
        if (trackValueRef || !canonicalizeValues)
        {
            return valueSerializer.Read(ref context, trackValueRef ? RefMode.Tracking : RefMode.None, readTypeInfo);
        }

        int start = context.Reader.Cursor;
        TValue value = valueSerializer.Read(ref context, RefMode.None, readTypeInfo);
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }
}
