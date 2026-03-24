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

using System.Buffers;

namespace Apache.Fory;

/// <summary>
/// Thread-safe wrapper around <see cref="Fory"/> based on one <see cref="Fory"/> instance per thread.
/// </summary>
public sealed class ThreadSafeFory : IDisposable
{
    private readonly Config _config;
    private readonly object _registrationLock = new();
    private readonly List<Action<Fory>> _registrations = [];
    private readonly ThreadLocal<Fory> _threadLocalFory;
    private bool _disposed;

    internal ThreadSafeFory(Config config)
    {
        _config = config;
        _threadLocalFory = new ThreadLocal<Fory>(CreatePerThreadFory, trackAllValues: true);
    }

    /// <summary>
    /// Gets the immutable runtime configuration shared by all thread-local runtimes.
    /// </summary>
    public Config Config => _config;

    /// <summary>
    /// Registers a user type by numeric type identifier for all current and future thread-local runtimes.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <param name="typeId">Numeric type identifier used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public ThreadSafeFory Register<T>(uint typeId)
    {
        ApplyRegistration(fory => fory.Register<T>(typeId));
        return this;
    }

    /// <summary>
    /// Registers a user type by name for all current and future thread-local runtimes.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <param name="typeName">Type name used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public ThreadSafeFory Register<T>(string typeName)
    {
        ApplyRegistration(fory => fory.Register<T>(typeName));
        return this;
    }

    /// <summary>
    /// Registers a user type by namespace and name for all current and future thread-local runtimes.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <param name="typeNamespace">Namespace used on the wire.</param>
    /// <param name="typeName">Type name used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public ThreadSafeFory Register<T>(string typeNamespace, string typeName)
    {
        ApplyRegistration(fory => fory.Register<T>(typeNamespace, typeName));
        return this;
    }

    /// <summary>
    /// Registers a user type by numeric type identifier with a custom serializer for all thread-local runtimes.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <typeparam name="TSerializer">Serializer implementation used for <typeparamref name="T"/>.</typeparam>
    /// <param name="typeId">Numeric type identifier used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public ThreadSafeFory Register<T, TSerializer>(uint typeId)
        where TSerializer : Serializer<T>, new()
    {
        ApplyRegistration(fory => fory.Register<T, TSerializer>(typeId));
        return this;
    }

    /// <summary>
    /// Registers a user type by namespace and name with a custom serializer for all thread-local runtimes.
    /// </summary>
    /// <typeparam name="T">Type to register.</typeparam>
    /// <typeparam name="TSerializer">Serializer implementation used for <typeparamref name="T"/>.</typeparam>
    /// <param name="typeNamespace">Namespace used on the wire.</param>
    /// <param name="typeName">Type name used on the wire.</param>
    /// <returns>The same runtime instance.</returns>
    public ThreadSafeFory Register<T, TSerializer>(string typeNamespace, string typeName)
        where TSerializer : Serializer<T>, new()
    {
        ApplyRegistration(fory => fory.Register<T, TSerializer>(typeNamespace, typeName));
        return this;
    }

    /// <summary>
    /// Serializes a value into a new byte array containing one Fory frame.
    /// </summary>
    /// <typeparam name="T">Value type.</typeparam>
    /// <param name="value">Value to serialize.</param>
    /// <returns>Serialized bytes.</returns>
    public byte[] Serialize<T>(in T value)
    {
        return Current.Serialize(in value);
    }

    /// <summary>
    /// Serializes a value and writes one Fory frame into the provided buffer writer.
    /// </summary>
    /// <typeparam name="T">Value type.</typeparam>
    /// <param name="output">Destination writer.</param>
    /// <param name="value">Value to serialize.</param>
    public void Serialize<T>(IBufferWriter<byte> output, in T value)
    {
        Current.Serialize(output, in value);
    }

    /// <summary>
    /// Deserializes a value from one Fory frame in the provided span.
    /// </summary>
    /// <typeparam name="T">Target type.</typeparam>
    /// <param name="payload">Serialized bytes containing exactly one frame.</param>
    /// <returns>Deserialized value.</returns>
    public T Deserialize<T>(ReadOnlySpan<byte> payload)
    {
        return Current.Deserialize<T>(payload);
    }

    /// <summary>
    /// Deserializes a value from the head of a framed sequence and advances the sequence.
    /// </summary>
    /// <typeparam name="T">Target type.</typeparam>
    /// <param name="payload">Input sequence. On success, sliced past the consumed frame.</param>
    /// <returns>Deserialized value.</returns>
    public T Deserialize<T>(ref ReadOnlySequence<byte> payload)
    {
        return Current.Deserialize<T>(ref payload);
    }

    /// <summary>
    /// Disposes thread-local runtimes and prevents further API use.
    /// </summary>
    public void Dispose()
    {
        lock (_registrationLock)
        {
            if (_disposed)
            {
                return;
            }

            _threadLocalFory.Dispose();
            _registrations.Clear();
            _disposed = true;
        }
    }

    private Fory Current
    {
        get
        {
            ThrowIfDisposed();
            return _threadLocalFory.Value!;
        }
    }

    private Fory CreatePerThreadFory()
    {
        Fory fory = new(_config);
        lock (_registrationLock)
        {
            if (_disposed)
            {
                throw new ObjectDisposedException(nameof(ThreadSafeFory));
            }

            foreach (Action<Fory> registration in _registrations)
            {
                registration(fory);
            }
        }

        return fory;
    }

    private void ApplyRegistration(Action<Fory> registration)
    {
        lock (_registrationLock)
        {
            ThrowIfDisposed();
            _registrations.Add(registration);
            foreach (Fory fory in _threadLocalFory.Values)
            {
                registration(fory);
            }
        }
    }

    private void ThrowIfDisposed()
    {
        if (_disposed)
        {
            throw new ObjectDisposedException(nameof(ThreadSafeFory));
        }
    }
}
