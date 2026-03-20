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

internal sealed class ReusableArray<T>
    where T : class
{
    private const int CopyThreshold = 128;
    private const int NilArraySize = 1024;
    private static readonly T?[] NilArray = new T?[NilArraySize];

    private T?[] _items;

    internal ReusableArray()
        : this(0)
    {
    }

    internal ReusableArray(int initialCapacity)
    {
        _items = new T?[initialCapacity];
    }

    internal int Count { get; private set; }

    internal void Add(T element)
    {
        T?[] items = _items;
        int count = Count;
        if (items.Length <= count)
        {
            T?[] grown = new T[(count + 1) * 2];
            Array.Copy(items, grown, items.Length);
            items = grown;
            _items = grown;
        }

        items[count] = element;
        Count = count + 1;
    }

    internal void Set(int index, T element)
    {
        _items[index] = element;
    }

    internal T? Get(int index)
    {
        return index >= 0 && index < Count ? _items[index] : null;
    }

    internal void Clear()
    {
        ClearObjectArray(_items, 0, Count);
        Count = 0;
    }

    private static void ClearObjectArray(T?[] items, int start, int count)
    {
        if (count < CopyThreshold)
        {
            Array.Clear(items, start, count);
            return;
        }

        if (count < NilArraySize)
        {
            Array.Copy(NilArray, 0, items, start, count);
            return;
        }

        int remaining = count;
        int current = start;
        while (remaining > NilArraySize)
        {
            Array.Copy(NilArray, 0, items, current, NilArraySize);
            remaining -= NilArraySize;
            current += NilArraySize;
        }

        Array.Copy(NilArray, 0, items, current, remaining);
    }
}
