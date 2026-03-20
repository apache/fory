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

using System.Numerics;
using System.Runtime.CompilerServices;

namespace Apache.Fory;

internal static class TypeMapKey
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal static ulong Get(Type type)
    {
        long value = type.TypeHandle.Value.ToInt64();
        return unchecked((ulong)value);
    }
}

/// <summary>
/// Open-addressed map keyed by <see cref="ulong"/>.
/// <para><see cref="ulong.MaxValue"/> is reserved as the empty-slot marker and must never be inserted.</para>
/// </summary>
internal sealed class UInt64Map<TValue>
{
    private const ulong EmptyKey = ulong.MaxValue;
    private const double DefaultLoadFactor = 0.5;
    private const ulong GoldenRatio = 0x9E3779B97F4A7C15;

    private struct Slot
    {
        public ulong Key;
        public TValue Value;
    }

    private Slot[] _entries;
    private int _tableCapacity;
    private int _mask;
    private int _shift;
    private int _count;
    private readonly double _loadFactor;
    private int _growThreshold;

    internal UInt64Map(int initialCapacity = 2, double loadFactor = DefaultLoadFactor)
    {
        _loadFactor = loadFactor;
        int capacity = NextPowerOfTwo(Math.Max(initialCapacity, 2));
        _entries = new Slot[capacity];
        _tableCapacity = capacity;
        _mask = capacity - 1;
        _shift = 64 - BitOperations.TrailingZeroCount((uint)capacity);
        _growThreshold = (int)(capacity * loadFactor);
        ClearEntries(_entries, capacity);
    }

    internal int Count => _count;

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal bool TryGetValue(ulong key, out TValue value)
    {
        int index = Place(key);
        while (true)
        {
            ref Slot slot = ref _entries[index];
            ulong slotKey = slot.Key;
            if (slotKey == key)
            {
                value = slot.Value;
                return true;
            }

            if (slotKey == EmptyKey)
            {
                value = default!;
                return false;
            }

            index = (index + 1) & _mask;
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal void Set(ulong key, TValue value)
    {
        if (_count >= _growThreshold)
        {
            Grow();
        }

        int index = FindSlotForInsert(key);
        ref Slot slot = ref _entries[index];
        if (slot.Key == EmptyKey)
        {
            slot.Key = key;
            _count += 1;
        }

        slot.Value = value;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    internal bool Remove(ulong key)
    {
        int index = Place(key);
        while (true)
        {
            ref Slot slot = ref _entries[index];
            ulong slotKey = slot.Key;
            if (slotKey == key)
            {
                RemoveEntry(index);
                return true;
            }

            if (slotKey == EmptyKey)
            {
                return false;
            }

            index = (index + 1) & _mask;
        }
    }

    internal void Clear()
    {
        if (_count == 0)
        {
            return;
        }

        ClearEntries(_entries, _tableCapacity);
        _count = 0;
    }

    internal void ClearKeys()
    {
        if (_count == 0)
        {
            return;
        }

        ClearEntryKeys(_entries, _tableCapacity);
        _count = 0;
    }

    internal void AddValuesTo(List<TValue> values)
    {
        for (int i = 0; i < _tableCapacity; i++)
        {
            if (_entries[i].Key != EmptyKey)
            {
                values.Add(_entries[i].Value);
            }
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private int FindSlotForInsert(ulong key)
    {
        int index = Place(key);
        while (true)
        {
            ulong slotKey = _entries[index].Key;
            if (slotKey == EmptyKey || slotKey == key)
            {
                return index;
            }

            index = (index + 1) & _mask;
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private int Place(ulong key)
    {
        return (int)((key * GoldenRatio) >> _shift);
    }

    private void Grow()
    {
        Slot[] oldEntries = _entries;
        int oldCapacity = _tableCapacity;
        int newCapacity = oldCapacity << 1;
        Slot[] newEntries = new Slot[newCapacity];
        ClearEntries(newEntries, newCapacity);

        _entries = newEntries;
        _tableCapacity = newCapacity;
        _mask = newCapacity - 1;
        _shift = 64 - BitOperations.TrailingZeroCount((uint)newCapacity);
        _growThreshold = (int)(newCapacity * _loadFactor);
        _count = 0;

        for (int i = 0; i < oldCapacity; i++)
        {
            Slot oldSlot = oldEntries[i];
            if (oldSlot.Key != EmptyKey)
            {
                int newIndex = FindSlotForInsert(oldSlot.Key);
                _entries[newIndex] = oldSlot;
                _count += 1;
            }
        }
    }

    private void RemoveEntry(int index)
    {
        int hole = index;
        int cursor = (hole + 1) & _mask;

        while (_entries[cursor].Key != EmptyKey)
        {
            int idealSlot = Place(_entries[cursor].Key);
            if (ShouldMoveEntry(idealSlot, cursor, hole))
            {
                _entries[hole] = _entries[cursor];
                hole = cursor;
            }

            cursor = (cursor + 1) & _mask;
        }

        _entries[hole].Key = EmptyKey;
        _entries[hole].Value = default!;
        _count -= 1;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static bool ShouldMoveEntry(int idealSlot, int candidateSlot, int emptySlot)
    {
        if (emptySlot <= candidateSlot)
        {
            return idealSlot <= emptySlot || idealSlot > candidateSlot;
        }

        return idealSlot <= emptySlot && idealSlot > candidateSlot;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static void ClearEntryKeys(Slot[] entries, int count)
    {
        for (int i = 0; i < count; i++)
        {
            entries[i].Key = EmptyKey;
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private static void ClearEntries(Slot[] entries, int count)
    {
        for (int i = 0; i < count; i++)
        {
            entries[i].Key = EmptyKey;
            entries[i].Value = default!;
        }
    }

    private static int NextPowerOfTwo(int value)
    {
        if (value <= 1)
        {
            return 1;
        }

        return 1 << (32 - BitOperations.LeadingZeroCount((uint)(value - 1)));
    }
}
