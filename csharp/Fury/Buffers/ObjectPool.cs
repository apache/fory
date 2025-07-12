using System;
using System.Collections.Concurrent;
using System.Threading;

namespace Fury.Buffers;

// Copy and modify from Microsoft.Extensions.ObjectPool.DefaultObjectPool<T>

/// <typeparam name="T">
/// The type to pool objects for.
/// </typeparam>
/// <remarks>
/// This implementation keeps a cache of retained objects.
/// This means that if objects are returned when the pool has already reached
/// "maximumRetained" objects they will be available to be Garbage Collected.
/// </remarks>
internal sealed class ObjectPool<T> : IDisposable
    where T : class
{
    private readonly Func<T> _factory;

    private readonly int _maxCapacity;
    private int _numItems;

    private readonly ConcurrentQueue<T> _items = new();
    private T? _fastItem;

    /// <summary>
    /// Creates an instance of <see cref="ObjectPool{T}"/>.
    /// </summary>
    public ObjectPool(Func<T> factory)
        : this(factory, Environment.ProcessorCount * 2) { }

    /// <summary>
    /// Creates an instance of <see cref="ObjectPool{T}"/>.
    /// </summary>
    /// <param name="factory">
    /// The factory method to create new instances of <typeparamref name="T"/>.
    /// </param>
    /// <param name="maximumRetained">
    /// The maximum number of objects to retain in the pool.
    /// </param>
    public ObjectPool(Func<T> factory, int maximumRetained)
    {
        _factory = factory;
        _maxCapacity = maximumRetained - 1; // -1 to account for _fastItem
    }

    public void Return(T obj)
    {
        if (_fastItem == null && Interlocked.CompareExchange(ref _fastItem, obj, null) == null)
        {
            return;
        }

        if (Interlocked.Increment(ref _numItems) <= _maxCapacity)
        {
            _items.Enqueue(obj);
        }

        // no room, clean up the count and drop the object on the floor
        Interlocked.Decrement(ref _numItems);
    }

    public T Rent()
    {
        var item = _fastItem;
        if (item != null && Interlocked.CompareExchange(ref _fastItem, null, item) == item)
        {
            return item;
        }

        if (_items.TryDequeue(out item))
        {
            Interlocked.Decrement(ref _numItems);
            return item;
        }

        return _factory();
    }

    public void Dispose()
    {
        if (_fastItem is IDisposable disposableFastItem)
        {
            disposableFastItem.Dispose();
        }

        while (_items.TryDequeue(out var item))
        {
            if (item is IDisposable disposableItem)
            {
                disposableItem.Dispose();
            }
        }
    }
}
