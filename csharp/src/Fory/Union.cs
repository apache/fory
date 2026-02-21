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

public class Union : IEquatable<Union>
{
    public Union(int index, object? value)
        : this(index, value, (int)ForyTypeId.Unknown)
    {
    }

    public Union(int index, object? value, int valueTypeId)
    {
        Index = index;
        Value = value;
        ValueTypeId = valueTypeId;
    }

    public int Index { get; }

    public object? Value { get; }

    public int ValueTypeId { get; }

    public bool HasValue => Value is not null;

    public T GetValue<T>()
    {
        if (Value is T typed)
        {
            return typed;
        }

        throw new InvalidOperationException(
            $"union value type mismatch: expected {typeof(T)}, got {Value?.GetType()}");
    }

    public bool Equals(Union? other)
    {
        return other is not null && Index == other.Index && Equals(Value, other.Value);
    }

    public override bool Equals(object? obj)
    {
        return obj is Union other && Equals(other);
    }

    public override int GetHashCode()
    {
        return HashCode.Combine(Index, Value);
    }

    public override string ToString()
    {
        return $"Union{{index={Index}, value={Value}}}";
    }
}

public sealed class Union2<T1, T2> : Union
{
    private Union2(int index, object? value)
        : this(index, value, (int)ForyTypeId.Unknown)
    {
    }

    private Union2(int index, object? value, int valueTypeId)
        : base(index, value, valueTypeId)
    {
        if (index is < 0 or > 1)
        {
            throw new ArgumentOutOfRangeException(nameof(index), $"Union2 index must be 0 or 1, got {index}");
        }
    }

    public static Union2<T1, T2> OfT1(T1 value)
    {
        return new Union2<T1, T2>(0, value);
    }

    public static Union2<T1, T2> OfT2(T2 value)
    {
        return new Union2<T1, T2>(1, value);
    }

    public static Union2<T1, T2> Of(int index, object? value)
    {
        return new Union2<T1, T2>(index, value);
    }

    public bool IsT1 => Index == 0;

    public bool IsT2 => Index == 1;

    public T1 GetT1()
    {
        if (!IsT1)
        {
            throw new InvalidOperationException($"Union2 currently holds case {Index}, not case 0");
        }

        return GetValue<T1>();
    }

    public T2 GetT2()
    {
        if (!IsT2)
        {
            throw new InvalidOperationException($"Union2 currently holds case {Index}, not case 1");
        }

        return GetValue<T2>();
    }

    public override string ToString()
    {
        return $"Union2{{index={Index}, value={Value}}}";
    }
}
