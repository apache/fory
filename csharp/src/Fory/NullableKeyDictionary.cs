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
public sealed class NullableKeyDictionary<TKey, TValue> : IDictionary<TKey, TValue>, IReadOnlyDictionary<TKey, TValue>
{
    private readonly Dictionary<TKey, TValue> _nonNullEntries;
    private bool _hasNullKey;
    private TValue _nullValue = default!;
    private KeyCollection? _keys;
    private ValueCollection? _values;

    public NullableKeyDictionary()
        : this((IEqualityComparer<TKey>?)null)
    {
    }

    public NullableKeyDictionary(int capacity)
        : this(capacity, null)
    {
    }

    public NullableKeyDictionary(IEqualityComparer<TKey>? comparer)
        : this(0, comparer)
    {
    }

    public NullableKeyDictionary(int capacity, IEqualityComparer<TKey>? comparer)
    {
        _nonNullEntries = comparer is null
            ? new Dictionary<TKey, TValue>(capacity)
            : new Dictionary<TKey, TValue>(capacity, comparer);
    }

    public NullableKeyDictionary(IDictionary<TKey, TValue> dictionary)
        : this(dictionary, null)
    {
    }

    public NullableKeyDictionary(IDictionary<TKey, TValue> dictionary, IEqualityComparer<TKey>? comparer)
        : this(dictionary?.Count ?? 0, comparer)
    {
        ArgumentNullException.ThrowIfNull(dictionary);
        foreach (KeyValuePair<TKey, TValue> entry in dictionary)
        {
            this[entry.Key] = entry.Value;
        }
    }

    public int Count => _nonNullEntries.Count + (_hasNullKey ? 1 : 0);

    public bool HasNullKey => _hasNullKey;

    public TValue NullKeyValue => _nullValue;

    public IEqualityComparer<TKey> Comparer => _nonNullEntries.Comparer;

    public IEnumerable<KeyValuePair<TKey, TValue>> NonNullEntries => _nonNullEntries;

    public ICollection<TKey> Keys => _keys ??= new KeyCollection(this);

    IEnumerable<TKey> IReadOnlyDictionary<TKey, TValue>.Keys => Keys;

    public ICollection<TValue> Values => _values ??= new ValueCollection(this);

    IEnumerable<TValue> IReadOnlyDictionary<TKey, TValue>.Values => Values;

    public TValue this[TKey key]
    {
        get
        {
            if (TryGetValue(key, out TValue value))
            {
                return value;
            }

            throw new KeyNotFoundException();
        }
        set => SetValue(key, value);
    }

    public bool IsReadOnly => false;

    public void Add(TKey key, TValue value)
    {
        if (key is null)
        {
            if (_hasNullKey)
            {
                throw new ArgumentException("An item with the same key has already been added.", nameof(key));
            }

            SetNullKeyValue(value);
            return;
        }

        _nonNullEntries.Add(key, value);
    }

    public bool ContainsKey(TKey key)
    {
        if (key is null)
        {
            return _hasNullKey;
        }

        return _nonNullEntries.ContainsKey(key);
    }

    public bool Remove(TKey key)
    {
        if (key is null)
        {
            if (!_hasNullKey)
            {
                return false;
            }

            _hasNullKey = false;
            _nullValue = default!;
            return true;
        }

        return _nonNullEntries.Remove(key);
    }

    public bool TryGetValue(TKey key, out TValue value)
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

    public void Add(KeyValuePair<TKey, TValue> item)
    {
        Add(item.Key, item.Value);
    }

    public bool Contains(KeyValuePair<TKey, TValue> item)
    {
        return TryGetValue(item.Key, out TValue value) &&
               EqualityComparer<TValue>.Default.Equals(value, item.Value);
    }

    public void CopyTo(KeyValuePair<TKey, TValue>[] array, int arrayIndex)
    {
        ArgumentNullException.ThrowIfNull(array);
        if (arrayIndex < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(arrayIndex));
        }

        if (array.Length - arrayIndex < Count)
        {
            throw new ArgumentException("The destination array is too small.", nameof(array));
        }

        if (_hasNullKey)
        {
            array[arrayIndex++] = new KeyValuePair<TKey, TValue>(default!, _nullValue);
        }

        foreach (KeyValuePair<TKey, TValue> entry in _nonNullEntries)
        {
            array[arrayIndex++] = entry;
        }
    }

    public bool Remove(KeyValuePair<TKey, TValue> item)
    {
        if (!Contains(item))
        {
            return false;
        }

        return Remove(item.Key);
    }

    public void Clear()
    {
        _nonNullEntries.Clear();
        _hasNullKey = false;
        _nullValue = default!;
    }

    internal void SetNullKeyValue(TValue value)
    {
        _hasNullKey = true;
        _nullValue = value;
    }

    private void SetValue(TKey key, TValue value)
    {
        if (key is null)
        {
            SetNullKeyValue(value);
            return;
        }

        _nonNullEntries[key] = value;
    }

    public IEnumerator<KeyValuePair<TKey, TValue>> GetEnumerator()
    {
        if (_hasNullKey)
        {
            yield return new KeyValuePair<TKey, TValue>(default!, _nullValue);
        }

        foreach (KeyValuePair<TKey, TValue> entry in _nonNullEntries)
        {
            yield return entry;
        }
    }

    IEnumerator IEnumerable.GetEnumerator()
    {
        return GetEnumerator();
    }

    private sealed class KeyCollection(NullableKeyDictionary<TKey, TValue> map) : ICollection<TKey>
    {
        private readonly NullableKeyDictionary<TKey, TValue> _map = map;

        public int Count => _map.Count;

        public bool IsReadOnly => true;

        public void Add(TKey item)
        {
            throw new NotSupportedException("Collection is read-only.");
        }

        public void Clear()
        {
            throw new NotSupportedException("Collection is read-only.");
        }

        public bool Contains(TKey item)
        {
            return _map.ContainsKey(item);
        }

        public void CopyTo(TKey[] array, int arrayIndex)
        {
            ArgumentNullException.ThrowIfNull(array);
            if (arrayIndex < 0)
            {
                throw new ArgumentOutOfRangeException(nameof(arrayIndex));
            }

            if (array.Length - arrayIndex < Count)
            {
                throw new ArgumentException("The destination array is too small.", nameof(array));
            }

            if (_map._hasNullKey)
            {
                array[arrayIndex++] = default!;
            }

            _map._nonNullEntries.Keys.CopyTo(array, arrayIndex);
        }

        public bool Remove(TKey item)
        {
            throw new NotSupportedException("Collection is read-only.");
        }

        public IEnumerator<TKey> GetEnumerator()
        {
            if (_map._hasNullKey)
            {
                yield return default!;
            }

            foreach (TKey key in _map._nonNullEntries.Keys)
            {
                yield return key;
            }
        }

        IEnumerator IEnumerable.GetEnumerator()
        {
            return GetEnumerator();
        }
    }

    private sealed class ValueCollection(NullableKeyDictionary<TKey, TValue> map) : ICollection<TValue>
    {
        private readonly NullableKeyDictionary<TKey, TValue> _map = map;

        public int Count => _map.Count;

        public bool IsReadOnly => true;

        public void Add(TValue item)
        {
            throw new NotSupportedException("Collection is read-only.");
        }

        public void Clear()
        {
            throw new NotSupportedException("Collection is read-only.");
        }

        public bool Contains(TValue item)
        {
            if (_map._hasNullKey && EqualityComparer<TValue>.Default.Equals(_map._nullValue, item))
            {
                return true;
            }

            return _map._nonNullEntries.Values.Contains(item);
        }

        public void CopyTo(TValue[] array, int arrayIndex)
        {
            ArgumentNullException.ThrowIfNull(array);
            if (arrayIndex < 0)
            {
                throw new ArgumentOutOfRangeException(nameof(arrayIndex));
            }

            if (array.Length - arrayIndex < Count)
            {
                throw new ArgumentException("The destination array is too small.", nameof(array));
            }

            if (_map._hasNullKey)
            {
                array[arrayIndex++] = _map._nullValue;
            }

            _map._nonNullEntries.Values.CopyTo(array, arrayIndex);
        }

        public bool Remove(TValue item)
        {
            throw new NotSupportedException("Collection is read-only.");
        }

        public IEnumerator<TValue> GetEnumerator()
        {
            if (_map._hasNullKey)
            {
                yield return _map._nullValue;
            }

            foreach (TValue value in _map._nonNullEntries.Values)
            {
                yield return value;
            }
        }

        IEnumerator IEnumerable.GetEnumerator()
        {
            return GetEnumerator();
        }
    }
}

public sealed class NullableKeyDictionarySerializer<TKey, TValue> : Serializer<NullableKeyDictionary<TKey, TValue>>
{
    public TypeId StaticTypeId => TypeId.Map;
    public bool IsNullableType => true;
    public bool IsReferenceTrackableType => true;
    public override NullableKeyDictionary<TKey, TValue> DefaultValue => null!;

    public override void WriteData(WriteContext context, in NullableKeyDictionary<TKey, TValue> value, bool hasGenerics)
    {
        Serializer<TKey> keySerializer = context.TypeResolver.GetSerializer<TKey>();
        Serializer<TValue> valueSerializer = context.TypeResolver.GetSerializer<TValue>();
        TypeInfo keyTypeInfo = context.TypeResolver.GetTypeInfo<TKey>();
        TypeInfo valueTypeInfo = context.TypeResolver.GetTypeInfo<TValue>();
        NullableKeyDictionary<TKey, TValue> map = value ?? new NullableKeyDictionary<TKey, TValue>();
        context.Writer.WriteVarUInt32((uint)map.Count);
        if (map.Count == 0)
        {
            return;
        }

        bool trackKeyRef = context.TrackRef && keyTypeInfo.IsReferenceTrackableType;
        bool trackValueRef = context.TrackRef && valueTypeInfo.IsReferenceTrackableType;
        bool keyDeclared = hasGenerics && !keyTypeInfo.StaticTypeId.NeedsTypeInfoForField();
        bool valueDeclared = hasGenerics && !valueTypeInfo.StaticTypeId.NeedsTypeInfoForField();
        bool keyDynamicType = keyTypeInfo.StaticTypeId == TypeId.Unknown;
        bool valueDynamicType = valueTypeInfo.StaticTypeId == TypeId.Unknown;
        KeyValuePair<TKey, TValue>[] pairs = [.. map];
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
                keySerializer,
                valueSerializer);
            return;
        }

        foreach (KeyValuePair<TKey, TValue> entry in pairs)
        {
            bool keyIsNull = entry.Key is null || keySerializer.IsNoneObject(entry.Key);
            bool valueIsNull = valueSerializer.IsNoneObject(entry.Value);
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
            else if (keyDeclared)
            {
                header |= DictionaryBits.DeclaredKeyType;
            }

            if (valueIsNull)
            {
                header |= DictionaryBits.ValueNull;
            }
            else if (valueDeclared)
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
                if (!valueDeclared)
                {
                    valueSerializer.WriteTypeInfo(context);
                }

                valueSerializer.Write(
                    context,
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
                    keySerializer.WriteTypeInfo(context);
                }

                keySerializer.Write(
                    context,
                    entry.Key!,
                    trackKeyRef ? RefMode.Tracking : RefMode.None,
                    false,
                    hasGenerics);
                continue;
            }

            context.Writer.WriteUInt8(1);
            if (!keyDeclared)
            {
                keySerializer.WriteTypeInfo(context);
            }

            if (!valueDeclared)
            {
                valueSerializer.WriteTypeInfo(context);
            }

            keySerializer.Write(
                context,
                entry.Key!,
                trackKeyRef ? RefMode.Tracking : RefMode.None,
                false,
                hasGenerics);
            valueSerializer.Write(
                context,
                entry.Value,
                trackValueRef ? RefMode.Tracking : RefMode.None,
                false,
                hasGenerics);
        }
    }

    public override NullableKeyDictionary<TKey, TValue> ReadData(ReadContext context)
    {
        Serializer<TKey> keySerializer = context.TypeResolver.GetSerializer<TKey>();
        Serializer<TValue> valueSerializer = context.TypeResolver.GetSerializer<TValue>();
        TypeInfo keyTypeInfo = context.TypeResolver.GetTypeInfo<TKey>();
        TypeInfo valueTypeInfo = context.TypeResolver.GetTypeInfo<TValue>();
        int totalLength = checked((int)context.Reader.ReadVarUInt32());
        if (totalLength == 0)
        {
            return new NullableKeyDictionary<TKey, TValue>();
        }

        NullableKeyDictionary<TKey, TValue> map = new();
        bool keyDynamicType = keyTypeInfo.StaticTypeId == TypeId.Unknown;
        bool valueDynamicType = valueTypeInfo.StaticTypeId == TypeId.Unknown;
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

            if (keyNull && valueNull)
            {
                map.SetNullKeyValue((TValue)valueSerializer.DefaultObject!);
                readCount += 1;
                continue;
            }

            if (keyNull)
            {
                TValue valueRead = ReadValueElement(
                    context,
                    trackValueRef,
                    !valueDeclared,
                    canonicalizeValues,
                    valueSerializer);

                map.SetNullKeyValue(valueRead);
                readCount += 1;
                continue;
            }

            if (valueNull)
            {
                TKey key = keySerializer.Read(
                    context,
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
                            keyDynamicInfo = context.TypeResolver.ReadDynamicTypeInfo(context);
                        }
                        else
                        {
                            keySerializer.ReadTypeInfo(context);
                        }
                    }

                    if (!valueDeclared)
                    {
                        if (valueDynamicType)
                        {
                            valueDynamicInfo = context.TypeResolver.ReadDynamicTypeInfo(context);
                        }
                        else
                        {
                            valueSerializer.ReadTypeInfo(context);
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

                    TValue valueRead = ReadValueElement(
                        context,
                        trackValueRef,
                        false,
                        canonicalizeValues,
                        valueSerializer);
                    if (valueDynamicInfo is not null)
                    {
                        context.ClearDynamicTypeInfo(typeof(TValue));
                    }

                    map[key] = valueRead;
                }

                readCount += chunkSize;
                continue;
            }

            if (!keyDeclared)
            {
                keySerializer.ReadTypeInfo(context);
            }

            if (!valueDeclared)
            {
                valueSerializer.ReadTypeInfo(context);
            }

            for (int i = 0; i < chunkSize; i++)
            {
                TKey key = keySerializer.Read(context, trackKeyRef ? RefMode.Tracking : RefMode.None, false);
                TValue valueRead = ReadValueElement(context, trackValueRef, false, canonicalizeValues, valueSerializer);
                map[key] = valueRead;
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
        Serializer<TKey> keySerializer,
        Serializer<TValue> valueSerializer)
    {
        foreach (KeyValuePair<TKey, TValue> pair in pairs)
        {
            bool keyIsNull = pair.Key is null || keySerializer.IsNoneObject(pair.Key);
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
                    pair.Key!,
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
                    DynamicAnyCodec.WriteAnyTypeInfo(pair.Key!, context);
                }
                else
                {
                    keySerializer.WriteTypeInfo(context);
                }
            }

            if (!valueDeclared)
            {
                if (valueDynamicType)
                {
                    DynamicAnyCodec.WriteAnyTypeInfo(pair.Value!, context);
                }
                else
                {
                    valueSerializer.WriteTypeInfo(context);
                }
            }

            keySerializer.Write(
                context,
                pair.Key!,
                trackKeyRef ? RefMode.Tracking : RefMode.None,
                false,
                hasGenerics);
            valueSerializer.Write(
                context,
                pair.Value,
                trackValueRef ? RefMode.Tracking : RefMode.None,
                false,
                hasGenerics);
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
#pragma warning restore CS8714
