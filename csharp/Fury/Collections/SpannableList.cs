using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using Fury.Helpers;

namespace Fury.Collections;

internal sealed class SpannableList<T> : IList<T>, IReadOnlyList<T>
{
    private static readonly bool NeedsClear = TypeHelper<T>.IsReferenceOrContainsReferences;

    private T[] _items = [];

    public int Count { get; private set; }
    public bool IsReadOnly => false;

    public SpannableList() { }

    public SpannableList(int capacity)
    {
        _items = new T[capacity];
    }

    public Enumerator GetEnumerator() => new(this);

    IEnumerator<T> IEnumerable<T>.GetEnumerator() => GetEnumerator();

    IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

    private void EnsureCapacity(int requiredCapacity)
    {
        if (requiredCapacity <= _items.Length)
        {
            return;
        }

        var newCapacity = Math.Max(_items.Length * 2, requiredCapacity);
        Array.Resize(ref _items, newCapacity);
    }

    void ICollection<T>.Add(T item) => Add(in item);

    public void Add(in T item)
    {
        EnsureCapacity(Count + 1);
        _items[Count++] = item;
    }

    public void Clear()
    {
        if (NeedsClear)
        {
            Array.Clear(_items, 0, Count);
        }
        Count = 0;
    }

    public bool Contains(T item) => IndexOf(item) != -1;

    public void CopyTo(T[] array, int arrayIndex)
    {
        Array.Copy(_items, 0, array, arrayIndex, Count);
    }

    bool ICollection<T>.Remove(T item) => Remove(in item);

    public bool Remove(in T item)
    {
        var index = IndexOf(item);
        if (index == -1)
        {
            return false;
        }

        RemoveAt(index);
        return true;
    }

    int IList<T>.IndexOf(T item) => IndexOf(item);

    public int IndexOf(in T item)
    {
        for (var i = 0; i < Count; i++)
        {
            if (EqualityComparer<T>.Default.Equals(_items[i], item))
            {
                return i;
            }
        }

        return -1;
    }

    void IList<T>.Insert(int index, T item) => Insert(index, in item);

    public void Insert(int index, in T item)
    {
        EnsureCapacity(Count + 1);
        Array.Copy(_items, index, _items, index + 1, Count - index);
        _items[index] = item;
        Count++;
    }

    public void RemoveAt(int index)
    {
        if (NeedsClear)
        {
            _items[index] = default!;
        }

        Count--;
        if (index < Count)
        {
            Array.Copy(_items, index + 1, _items, index, Count - index);
        }
    }

    public ref T this[int index]
    {
        get
        {
            if (index < 0 || index >= Count)
            {
                ThrowHelper.ThrowIndexOutOfRangeException();
            }

            return ref _items[index];
        }
    }

    T IReadOnlyList<T>.this[int index] => this[index];

    T IList<T>.this[int index]
    {
        get => this[index];
        set => this[index] = value;
    }

    public Span<T> AsSpan() => new(_items, 0, Count);

    public struct Enumerator() : IEnumerator<T>
    {
        private int _index = -1;
        private readonly SpannableList<T> _list;
        T IEnumerator<T>.Current => _list[_index];
        public ref T Current => ref _list[_index];

        internal Enumerator(SpannableList<T> list)
            : this()
        {
            _list = list;
        }

        public bool MoveNext()
        {
            _index++;
            return _index < _list.Count;
        }

        public void Reset()
        {
            _index = -1;
        }

        object? IEnumerator.Current => Current;

        public void Dispose()
        {
        }
    }
}
