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

#pragma warning disable CS8714
public sealed class ForyMap<TKey, TValue> : IEnumerable<KeyValuePair<TKey?, TValue>>
{
    private readonly Dictionary<TKey, TValue> _nonNullEntries;
    private bool _hasNullKey;
    private TValue _nullValue = default!;

    public ForyMap()
        : this(null)
    {
    }

    public ForyMap(IEqualityComparer<TKey>? comparer)
    {
        _nonNullEntries = comparer is null
            ? new Dictionary<TKey, TValue>()
            : new Dictionary<TKey, TValue>(comparer);
    }

    public int Count => _nonNullEntries.Count + (_hasNullKey ? 1 : 0);

    public bool HasNullKey => _hasNullKey;

    public TValue NullKeyValue => _nullValue;

    public IEnumerable<KeyValuePair<TKey, TValue>> NonNullEntries => _nonNullEntries;

    public void Add(TKey? key, TValue value)
    {
        if (key is null)
        {
            _hasNullKey = true;
            _nullValue = value;
            return;
        }

        _nonNullEntries[key] = value;
    }

    public bool TryGetValue(TKey? key, out TValue value)
    {
        if (key is null)
        {
            if (_hasNullKey)
            {
                value = _nullValue;
                return true;
            }

            value = default!;
            return false;
        }

        return _nonNullEntries.TryGetValue(key, out value!);
    }

    public void Clear()
    {
        _nonNullEntries.Clear();
        _hasNullKey = false;
        _nullValue = default!;
    }

    public IEnumerator<KeyValuePair<TKey?, TValue>> GetEnumerator()
    {
        if (_hasNullKey)
        {
            yield return new KeyValuePair<TKey?, TValue>(default, _nullValue);
        }

        foreach (KeyValuePair<TKey, TValue> entry in _nonNullEntries)
        {
            yield return new KeyValuePair<TKey?, TValue>(entry.Key, entry.Value);
        }
    }

    IEnumerator IEnumerable.GetEnumerator()
    {
        return GetEnumerator();
    }
}

public sealed class ForyMapSerializer<TKey, TValue> : Serializer<ForyMap<TKey, TValue>>
{
    private readonly Serializer<TKey> _keySerializer = SerializerRegistry.Get<TKey>();
    private readonly Serializer<TValue> _valueSerializer = SerializerRegistry.Get<TValue>();

    public override ForyTypeId StaticTypeId => ForyTypeId.Map;
    public override bool IsNullableType => true;
    public override bool IsReferenceTrackableType => true;
    public override ForyMap<TKey, TValue> DefaultValue => null!;
    public override bool IsNone(ForyMap<TKey, TValue> value) => value is null;

    public override void WriteData(ref WriteContext context, in ForyMap<TKey, TValue> value, bool hasGenerics)
    {
        ForyMap<TKey, TValue> map = value ?? new ForyMap<TKey, TValue>();
        context.Writer.WriteVarUInt32((uint)map.Count);
        if (map.Count == 0)
        {
            return;
        }

        bool trackKeyRef = context.TrackRef && _keySerializer.IsReferenceTrackableType;
        bool trackValueRef = context.TrackRef && _valueSerializer.IsReferenceTrackableType;
        bool keyDeclared = hasGenerics && !_keySerializer.StaticTypeId.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !_valueSerializer.StaticTypeId.NeedsTypeInfoForField();

        foreach (KeyValuePair<TKey?, TValue> entry in map)
        {
            bool keyIsNull = entry.Key is null || _keySerializer.IsNoneObject(entry.Key);
            bool valueIsNull = _valueSerializer.IsNoneObject(entry.Value);
            byte header = 0;
            if (trackKeyRef)
            {
                header |= MapBits.TrackingKeyRef;
            }

            if (trackValueRef)
            {
                header |= MapBits.TrackingValueRef;
            }

            if (keyIsNull)
            {
                header |= MapBits.KeyNull;
            }
            else if (keyDeclared)
            {
                header |= MapBits.DeclaredKeyType;
            }

            if (valueIsNull)
            {
                header |= MapBits.ValueNull;
            }
            else if (valueDeclared)
            {
                header |= MapBits.DeclaredValueType;
            }

            context.Writer.WriteUInt8(header);
            if (keyIsNull && valueIsNull)
            {
                continue;
            }

            if (keyIsNull)
            {
                if (!valueDeclared)
                {
                    _valueSerializer.WriteTypeInfo(ref context);
                }

                _valueSerializer.Write(
                    ref context,
                    entry.Value,
                    trackValueRef ? RefMode.Tracking : RefMode.None,
                    false,
                    hasGenerics);
                continue;
            }

            if (valueIsNull)
            {
                if (!keyDeclared)
                {
                    _keySerializer.WriteTypeInfo(ref context);
                }

                _keySerializer.Write(
                    ref context,
                    entry.Key!,
                    trackKeyRef ? RefMode.Tracking : RefMode.None,
                    false,
                    hasGenerics);
                continue;
            }

            context.Writer.WriteUInt8(1);
            if (!keyDeclared)
            {
                _keySerializer.WriteTypeInfo(ref context);
            }

            if (!valueDeclared)
            {
                _valueSerializer.WriteTypeInfo(ref context);
            }

            _keySerializer.Write(
                ref context,
                entry.Key!,
                trackKeyRef ? RefMode.Tracking : RefMode.None,
                false,
                hasGenerics);
            _valueSerializer.Write(
                ref context,
                entry.Value,
                trackValueRef ? RefMode.Tracking : RefMode.None,
                false,
                hasGenerics);
        }
    }

    public override ForyMap<TKey, TValue> ReadData(ref ReadContext context)
    {
        int totalLength = checked((int)context.Reader.ReadVarUInt32());
        if (totalLength == 0)
        {
            return new ForyMap<TKey, TValue>();
        }

        ForyMap<TKey, TValue> map = new();
        bool keyDynamicType = _keySerializer.StaticTypeId == ForyTypeId.Unknown;
        bool valueDynamicType = _valueSerializer.StaticTypeId == ForyTypeId.Unknown;
        bool canonicalizeValues = context.TrackRef && _valueSerializer.IsReferenceTrackableType;

        int readCount = 0;
        while (readCount < totalLength)
        {
            byte header = context.Reader.ReadUInt8();
            bool trackKeyRef = (header & MapBits.TrackingKeyRef) != 0;
            bool keyNull = (header & MapBits.KeyNull) != 0;
            bool keyDeclared = (header & MapBits.DeclaredKeyType) != 0;
            bool trackValueRef = (header & MapBits.TrackingValueRef) != 0;
            bool valueNull = (header & MapBits.ValueNull) != 0;
            bool valueDeclared = (header & MapBits.DeclaredValueType) != 0;

            if (keyNull && valueNull)
            {
                map.Add(default, (TValue)_valueSerializer.DefaultObject!);
                readCount += 1;
                continue;
            }

            if (keyNull)
            {
                TValue value = ReadValueElement(
                    ref context,
                    trackValueRef,
                    valueDynamicType || !valueDeclared,
                    canonicalizeValues,
                    _valueSerializer);
                map.Add(default, value);
                readCount += 1;
                continue;
            }

            if (valueNull)
            {
                TKey key = (TKey)_keySerializer.Read(ref context, trackKeyRef ? RefMode.Tracking : RefMode.None, keyDynamicType || !keyDeclared)!;
                map.Add(key, (TValue)_valueSerializer.DefaultObject!);
                readCount += 1;
                continue;
            }

            int chunkSize = context.Reader.ReadUInt8();
            if (!keyDeclared)
            {
                _keySerializer.ReadTypeInfo(ref context);
            }

            if (!valueDeclared)
            {
                _valueSerializer.ReadTypeInfo(ref context);
            }

            for (int i = 0; i < chunkSize; i++)
            {
                TKey key = (TKey)_keySerializer.Read(ref context, trackKeyRef ? RefMode.Tracking : RefMode.None, false)!;
                TValue value = ReadValueElement(ref context, trackValueRef, false, canonicalizeValues, _valueSerializer);
                map.Add(key, value);
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

    private static TValue ReadValueElement(
        ref ReadContext context,
        bool trackValueRef,
        bool readTypeInfo,
        bool canonicalizeValues,
        ISerializer valueSerializer)
    {
        if (trackValueRef || !canonicalizeValues)
        {
            return (TValue)valueSerializer.Read(ref context, trackValueRef ? RefMode.Tracking : RefMode.None, readTypeInfo)!;
        }

        int start = context.Reader.Cursor;
        TValue value = (TValue)valueSerializer.Read(ref context, RefMode.None, readTypeInfo)!;
        int end = context.Reader.Cursor;
        return context.CanonicalizeNonTrackingReference(value, start, end);
    }
}
#pragma warning restore CS8714
