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

namespace Fory.ExternalTypes;

public class ExternalUser : IComparable<ExternalUser>, IEquatable<ExternalUser>
{
    public int Id { get; set; }

    public string Name { get; set; } = string.Empty;

    public ExternalUser? Friend { get; set; }

    public List<ExternalUser> Links { get; set; } = [];

    public int CompareTo(ExternalUser? other)
    {
        return other is null ? 1 : Id.CompareTo(other.Id);
    }

    public bool Equals(ExternalUser? other)
    {
        return other is not null && Id == other.Id && Name == other.Name;
    }

    public override bool Equals(object? obj)
    {
        return obj is ExternalUser other && Equals(other);
    }

    public override int GetHashCode()
    {
        return HashCode.Combine(Id, Name);
    }
}

public struct ExternalPoint : IEquatable<ExternalPoint>
{
    public int X { get; set; }

    public int Y { get; set; }

    public readonly bool Equals(ExternalPoint other)
    {
        return X == other.X && Y == other.Y;
    }

    public override readonly bool Equals(object? obj)
    {
        return obj is ExternalPoint other && Equals(other);
    }

    public override readonly int GetHashCode()
    {
        return HashCode.Combine(X, Y);
    }
}

public enum ExternalStatus : uint
{
    Unknown = 0,
    Ready = 1,
    Done = 2,
    Complete = Done,
}

public sealed class ExternalBox<T>
{
    public T Value { get; set; } = default!;
}

public class ExternalBase
{
    public string BaseName { get; set; } = string.Empty;
}

public sealed class ExternalDerived : ExternalBase
{
    public int Id { get; set; }
}

public class ExternalHiddenBase
{
    public int Value;
}

public sealed class ExternalHiddenDerived : ExternalHiddenBase
{
    public new int Value;
}

public sealed class ExternalFields
{
    public int Count;

    public string Name { get; set; } = string.Empty;

    public int @event { get; set; }
}

public sealed class ExternalSchemaModel
{
    public int FixedValue { get; set; }

    public long TaggedValue { get; set; }

    public int[] ArrayValue { get; set; } = [];

    public List<int> ListValue { get; set; } = [];

    public HashSet<int> SetValue { get; set; } = [];

    public Dictionary<uint, List<ulong?>?> NestedValue { get; set; } = [];
}

public sealed class ExternalVersionOne
{
    public int Id { get; set; }

    public string Name { get; set; } = string.Empty;
}

public sealed class ExternalVersionTwo
{
    public string Name { get; set; } = string.Empty;

    public int Id { get; set; }

    public long Added { get; set; }
}

public sealed class ExternalVersionRenamed
{
    public int Identifier { get; set; }

    public string Name { get; set; } = string.Empty;
}

public sealed class ExternalEvolutionOff
{
    public int Value { get; set; }
}

public struct ExternalBudgetValue
{
    public long Left;

    public long Right;
}

public class ExternalBudgetBase
{
    public ExternalBudgetValue BaseState;
}

public sealed class ExternalBudgetModel : ExternalBudgetBase
{
    public int Value;

    public ExternalBudgetValue PublicState;

    private readonly ExternalBudgetValue HiddenState;

    public ExternalBudgetModel()
    {
    }

    public ExternalBudgetModel(ExternalBudgetValue hiddenState)
    {
        HiddenState = hiddenState;
    }

    public ExternalBudgetValue ReadHiddenState()
    {
        return HiddenState;
    }
}

public class ExternalPrivateBase
{
    private long _identifier;
    private string Secret { get; set; } = string.Empty;
    private readonly int _cache = 29;

    public void SetPrivateState(long identifier, string secret)
    {
        _identifier = identifier;
        Secret = secret;
    }

    public (long Identifier, string Secret, int Cache) ReadPrivateState()
    {
        return (_identifier, Secret, _cache);
    }
}

public class ExternalPrivateDerived : ExternalPrivateBase
{
    public int PublicValue;
}

public class ExternalGenericBase<T>
{
    public T Value { get; set; } = default!;
}

public sealed class XlangUser
{
    public int Id { get; set; }

    public string Name { get; set; } = string.Empty;
}

public struct XlangPoint
{
    public int X { get; set; }

    public int Y { get; set; }
}

public enum XlangStatus : uint
{
    Unknown = 0,
    Ready = 7,
    Done = 23,
}

public sealed class XlangHolder
{
    public List<XlangUser> Users { get; set; } = [];

    public Dictionary<string, XlangUser> UsersByName { get; set; } = [];
}
