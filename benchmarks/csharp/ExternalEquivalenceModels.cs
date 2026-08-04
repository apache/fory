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

using Apache.Fory;
using ExternalPoint = Apache.Fory.Benchmarks.CSharp.ExternalTypes.Point;
using ExternalUser = Apache.Fory.Benchmarks.CSharp.ExternalTypes.User;

namespace Apache.Fory.Benchmarks.CSharp;

[ForyStruct]
internal sealed class OrdinaryUser
{
    [ForyField(1)]
    public int Id { get; set; }

    [ForyField(2)]
    public long Score { get; set; }

    [ForyField(3)]
    public string Name { get; set; } = string.Empty;
}

[ForyStruct(Target = typeof(ExternalUser))]
internal abstract class UserSerializer
{
    [ForyField(1)]
    public abstract int Id { get; }

    [ForyField(2)]
    public abstract long Score { get; }

    [ForyField(3)]
    public abstract string Name { get; }

    [ForyField(
        Ignore = true,
        TargetDeclaringType = typeof(ExternalUser),
        TargetMemberName = "<Id>k__BackingField")]
    public abstract int IdStorage { get; }

    [ForyField(
        Ignore = true,
        TargetDeclaringType = typeof(ExternalUser),
        TargetMemberName = "<Score>k__BackingField")]
    public abstract long ScoreStorage { get; }

    [ForyField(
        Ignore = true,
        TargetDeclaringType = typeof(ExternalUser),
        TargetMemberName = "<Name>k__BackingField")]
    public abstract string NameStorage { get; }
}

[ForyStruct]
internal struct OrdinaryPoint
{
    [ForyField(1)]
    public int X { get; set; }

    [ForyField(2)]
    public long Y { get; set; }
}

[ForyStruct(Target = typeof(ExternalPoint))]
internal abstract class PointSerializer
{
    [ForyField(1)]
    public abstract int X { get; }

    [ForyField(2)]
    public abstract long Y { get; }
}

[ForyStruct]
internal sealed class OrdinaryUserHolder
{
    [ForyField(1)]
    public OrdinaryUser Value { get; set; } = new();
}

[ForyStruct]
internal sealed class ExternalUserHolder
{
    [ForyField(1)]
    public ExternalUser Value { get; set; } = new();
}

[ForyStruct]
internal sealed class OrdinaryListHolder
{
    [ForyField(1)]
    public List<OrdinaryUser> Values { get; set; } = [];
}

[ForyStruct]
internal sealed class ExternalListHolder
{
    [ForyField(1)]
    public List<ExternalUser> Values { get; set; } = [];
}

[ForyStruct]
internal sealed class OrdinaryMapHolder
{
    [ForyField(1)]
    public Dictionary<string, OrdinaryUser> Values { get; set; } = [];
}

[ForyStruct]
internal sealed class ExternalMapHolder
{
    [ForyField(1)]
    public Dictionary<string, ExternalUser> Values { get; set; } = [];
}

internal static class EquivalenceData
{
    private const int ElementCount = 8;

    public static OrdinaryUser CreateOrdinaryUser()
    {
        return new OrdinaryUser
        {
            Id = 7,
            Score = 123_456_789,
            Name = "external-type-benchmark",
        };
    }

    public static ExternalUser CreateExternalUser()
    {
        return new ExternalUser
        {
            Id = 7,
            Score = 123_456_789,
            Name = "external-type-benchmark",
        };
    }

    public static OrdinaryPoint CreateOrdinaryPoint()
    {
        return new OrdinaryPoint
        {
            X = 17,
            Y = 987_654_321,
        };
    }

    public static ExternalPoint CreateExternalPoint()
    {
        return new ExternalPoint
        {
            X = 17,
            Y = 987_654_321,
        };
    }

    public static List<OrdinaryUser> CreateOrdinaryList()
    {
        List<OrdinaryUser> values = new(ElementCount);
        for (int i = 0; i < ElementCount; i++)
        {
            values.Add(new OrdinaryUser
            {
                Id = i,
                Score = 100_000L + i,
                Name = $"user-{i}",
            });
        }

        return values;
    }

    public static List<ExternalUser> CreateExternalList()
    {
        List<ExternalUser> values = new(ElementCount);
        for (int i = 0; i < ElementCount; i++)
        {
            values.Add(new ExternalUser
            {
                Id = i,
                Score = 100_000L + i,
                Name = $"user-{i}",
            });
        }

        return values;
    }

    public static Dictionary<string, OrdinaryUser> CreateOrdinaryMap()
    {
        Dictionary<string, OrdinaryUser> values = new(ElementCount, StringComparer.Ordinal);
        for (int i = 0; i < ElementCount; i++)
        {
            values.Add(
                $"key-{i}",
                new OrdinaryUser
                {
                    Id = i,
                    Score = 100_000L + i,
                    Name = $"user-{i}",
                });
        }

        return values;
    }

    public static Dictionary<string, ExternalUser> CreateExternalMap()
    {
        Dictionary<string, ExternalUser> values = new(ElementCount, StringComparer.Ordinal);
        for (int i = 0; i < ElementCount; i++)
        {
            values.Add(
                $"key-{i}",
                new ExternalUser
                {
                    Id = i,
                    Score = 100_000L + i,
                    Name = $"user-{i}",
                });
        }

        return values;
    }
}
