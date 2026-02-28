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

/// <summary>
/// Immutable runtime configuration used by <see cref="Fory"/> and <see cref="ThreadSafeFory"/>.
/// </summary>
/// <param name="Xlang">Whether cross-language protocol mode is enabled.</param>
/// <param name="TrackRef">Whether shared and circular reference tracking is enabled.</param>
/// <param name="Compatible">Whether schema-compatible mode is enabled.</param>
/// <param name="CheckStructVersion">Whether generated struct schema hash checks are enforced.</param>
/// <param name="MaxDepth">Maximum allowed nesting depth for dynamic object payload reads.</param>
public sealed record Config(
    bool Xlang = true,
    bool TrackRef = false,
    bool Compatible = false,
    bool CheckStructVersion = false,
    int MaxDepth = 20);

/// <summary>
/// Fluent builder for creating <see cref="Fory"/> and <see cref="ThreadSafeFory"/> runtimes.
/// </summary>
public sealed class ForyBuilder
{
    private bool _xlang = true;
    private bool _trackRef;
    private bool _compatible;
    private bool _checkStructVersion;
    private int _maxDepth = 20;

    /// <summary>
    /// Enables or disables cross-language protocol mode.
    /// </summary>
    /// <param name="enabled">Whether to enable cross-language mode. Defaults to <c>true</c>.</param>
    /// <returns>The same builder instance.</returns>
    public ForyBuilder Xlang(bool enabled = true)
    {
        _xlang = enabled;
        return this;
    }

    /// <summary>
    /// Enables or disables reference tracking for shared and circular object graphs.
    /// </summary>
    /// <param name="enabled">Whether to enable reference tracking. Defaults to <c>false</c>.</param>
    /// <returns>The same builder instance.</returns>
    public ForyBuilder TrackRef(bool enabled = false)
    {
        _trackRef = enabled;
        return this;
    }

    /// <summary>
    /// Enables or disables schema-compatible mode for schema evolution scenarios.
    /// </summary>
    /// <param name="enabled">Whether to enable compatible mode. Defaults to <c>false</c>.</param>
    /// <returns>The same builder instance.</returns>
    public ForyBuilder Compatible(bool enabled = false)
    {
        _compatible = enabled;
        return this;
    }

    /// <summary>
    /// Enables or disables generated struct schema hash validation.
    /// </summary>
    /// <param name="enabled">Whether to enforce struct version checks. Defaults to <c>false</c>.</param>
    /// <returns>The same builder instance.</returns>
    public ForyBuilder CheckStructVersion(bool enabled = false)
    {
        _checkStructVersion = enabled;
        return this;
    }

    /// <summary>
    /// Sets the maximum supported dynamic object nesting depth during deserialization.
    /// </summary>
    /// <param name="value">Depth limit. Must be greater than <c>0</c>.</param>
    /// <returns>The same builder instance.</returns>
    /// <exception cref="ArgumentOutOfRangeException">Thrown when <paramref name="value"/> is less than or equal to <c>0</c>.</exception>
    public ForyBuilder MaxDepth(int value)
    {
        if (value <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "MaxDepth must be greater than 0.");
        }

        _maxDepth = value;
        return this;
    }

    private Config BuildConfig()
    {
        return new Config(
            Xlang: _xlang,
            TrackRef: _trackRef,
            Compatible: _compatible,
            CheckStructVersion: _checkStructVersion,
            MaxDepth: _maxDepth);
    }

    /// <summary>
    /// Builds a single-threaded <see cref="Fory"/> instance.
    /// </summary>
    /// <returns>A configured <see cref="Fory"/> runtime.</returns>
    public Fory Build()
    {
        return new Fory(BuildConfig());
    }

    /// <summary>
    /// Builds a multi-thread-safe wrapper that keeps one <see cref="Fory"/> per thread.
    /// </summary>
    /// <returns>A configured <see cref="ThreadSafeFory"/> runtime.</returns>
    public ThreadSafeFory BuildThreadSafe()
    {
        return new ThreadSafeFory(BuildConfig());
    }
}
