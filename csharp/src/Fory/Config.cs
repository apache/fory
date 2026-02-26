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

public sealed record Config(
    bool Xlang = true,
    bool TrackRef = false,
    bool Compatible = false,
    bool CheckStructVersion = false,
    int MaxDepth = 20);

public sealed class ForyBuilder
{
    private bool _xlang = true;
    private bool _trackRef;
    private bool _compatible;
    private bool _checkStructVersion;
    private int _maxDepth = 20;

    public ForyBuilder Xlang(bool enabled = true)
    {
        _xlang = enabled;
        return this;
    }

    public ForyBuilder TrackRef(bool enabled = false)
    {
        _trackRef = enabled;
        return this;
    }

    public ForyBuilder Compatible(bool enabled = false)
    {
        _compatible = enabled;
        return this;
    }

    public ForyBuilder CheckStructVersion(bool enabled = false)
    {
        _checkStructVersion = enabled;
        return this;
    }

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
    /// Builds a single-thread <see cref="Fory"/> instance.
    /// </summary>
    public Fory Build()
    {
        return new Fory(BuildConfig());
    }

    /// <summary>
    /// Builds a multi-thread-safe wrapper that keeps one <see cref="Fory"/> per thread.
    /// </summary>
    public ThreadSafeFory BuildThreadSafe()
    {
        return new ThreadSafeFory(BuildConfig());
    }
}
