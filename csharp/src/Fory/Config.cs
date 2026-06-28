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
/// Instances are created by <see cref="ForyBuilder"/>.
/// </summary>
public sealed class Config
{
    internal Config(
        bool trackRef,
        bool compatible,
        bool checkStructVersion,
        int maxDepth,
        int maxTypeFields,
        int maxTypeMetaBytes,
        int maxSchemaVersionsPerType,
        int maxAverageSchemaVersionsPerType)
    {
        if (maxDepth <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maxDepth), "MaxDepth must be greater than 0.");
        }
        if (maxTypeFields <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maxTypeFields), "MaxTypeFields must be greater than 0.");
        }
        if (maxTypeMetaBytes <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maxTypeMetaBytes), "MaxTypeMetaBytes must be greater than 0.");
        }
        if (maxSchemaVersionsPerType <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maxSchemaVersionsPerType), "MaxSchemaVersionsPerType must be greater than 0.");
        }
        if (maxAverageSchemaVersionsPerType <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maxAverageSchemaVersionsPerType), "MaxAverageSchemaVersionsPerType must be greater than 0.");
        }

        TrackRef = trackRef;
        Compatible = compatible;
        CheckStructVersion = checkStructVersion;
        MaxDepth = maxDepth;
        MaxTypeFields = maxTypeFields;
        MaxTypeMetaBytes = maxTypeMetaBytes;
        MaxSchemaVersionsPerType = maxSchemaVersionsPerType;
        MaxAverageSchemaVersionsPerType = maxAverageSchemaVersionsPerType;
    }

    /// <summary>
    /// Gets whether shared and circular reference tracking is enabled.
    /// </summary>
    public bool TrackRef { get; }

    /// <summary>
    /// Gets whether schema-compatible mode is enabled.
    /// </summary>
    public bool Compatible { get; }

    /// <summary>
    /// Gets whether generated struct schema hash checks are enforced.
    /// </summary>
    public bool CheckStructVersion { get; }

    /// <summary>
    /// Gets the maximum allowed nesting depth for dynamic object payload reads.
    /// </summary>
    public int MaxDepth { get; }

    /// <summary>
    /// Gets the maximum accepted field count in one received struct TypeMeta.
    /// </summary>
    public int MaxTypeFields { get; }

    /// <summary>
    /// Gets the maximum accepted body size in one received TypeMeta.
    /// </summary>
    public int MaxTypeMetaBytes { get; }

    /// <summary>
    /// Gets the maximum accepted remote metadata versions for one logical type.
    /// </summary>
    public int MaxSchemaVersionsPerType { get; }

    /// <summary>
    /// Gets the average remote metadata version limit across accepted remote types.
    /// </summary>
    public int MaxAverageSchemaVersionsPerType { get; }
}

/// <summary>
/// Fluent builder for creating <see cref="Fory"/> and <see cref="ThreadSafeFory"/> runtimes.
/// </summary>
public sealed class ForyBuilder
{
    private bool _trackRef;
    private bool? _compatible;
    private bool _checkStructVersion;
    private int _maxDepth = 20;
    private int _maxTypeFields = 512;
    private int _maxTypeMetaBytes = 4096;
    private int _maxSchemaVersionsPerType = 10;
    private int _maxAverageSchemaVersionsPerType = 3;

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

    /// <summary>
    /// Sets the maximum accepted field count in one received struct TypeMeta.
    /// </summary>
    public ForyBuilder MaxTypeFields(int value)
    {
        if (value <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "MaxTypeFields must be greater than 0.");
        }

        _maxTypeFields = value;
        return this;
    }

    /// <summary>
    /// Sets the maximum accepted body size in one received TypeMeta.
    /// </summary>
    public ForyBuilder MaxTypeMetaBytes(int value)
    {
        if (value <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "MaxTypeMetaBytes must be greater than 0.");
        }

        _maxTypeMetaBytes = value;
        return this;
    }

    /// <summary>
    /// Sets the maximum accepted remote metadata versions for one logical type.
    /// </summary>
    public ForyBuilder MaxSchemaVersionsPerType(int value)
    {
        if (value <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "MaxSchemaVersionsPerType must be greater than 0.");
        }

        _maxSchemaVersionsPerType = value;
        return this;
    }

    /// <summary>
    /// Sets the average remote metadata version limit across accepted remote types.
    /// </summary>
    public ForyBuilder MaxAverageSchemaVersionsPerType(int value)
    {
        if (value <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "MaxAverageSchemaVersionsPerType must be greater than 0.");
        }

        _maxAverageSchemaVersionsPerType = value;
        return this;
    }

    private Config BuildConfig()
    {
        bool compatible = _compatible ?? true;
        // Compatible mode carries field metadata for evolution; schema hash checks
        // belong only to schema-consistent mode.
        return new Config(
            trackRef: _trackRef,
            compatible: compatible,
            checkStructVersion: compatible ? false : _checkStructVersion,
            maxDepth: _maxDepth,
            maxTypeFields: _maxTypeFields,
            maxTypeMetaBytes: _maxTypeMetaBytes,
            maxSchemaVersionsPerType: _maxSchemaVersionsPerType,
            maxAverageSchemaVersionsPerType: _maxAverageSchemaVersionsPerType);
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
