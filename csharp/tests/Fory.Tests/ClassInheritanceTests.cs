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

using System.Reflection;
using Apache.Fory;
using Fory.ExternalTypes;
using Fory.InheritanceConsumer;
using Fory.InheritanceProviders;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.Tests;

[ForyStruct]
internal abstract class InheritedRoot
{
    [ForyField(1)]
    private int PrivateValue { get; set; }

    [ForyField(2)]
    protected string ProtectedText { get; set; } = string.Empty;

    [ForyField(3)]
    public int PublicValue;

    [ForyField(4)]
    public InheritedLeaf? Self { get; set; }

    [ForyField(7)]
    public int HiddenValue;

    public abstract event Action? AbstractChanged;

    private readonly long _cache = 41;

    public void SetRootState(int privateValue, string protectedText)
    {
        PrivateValue = privateValue;
        ProtectedText = protectedText;
    }

    public (int PrivateValue, string ProtectedText, long Cache) RootState()
    {
        return (PrivateValue, ProtectedText, _cache);
    }
}

[ForyStruct]
internal class InheritedMiddle : InheritedRoot
{
    [ForyField(5)]
    public virtual int VirtualValue { get; set; }

    public override event Action? AbstractChanged;

    public void RaiseAbstractChanged()
    {
        AbstractChanged?.Invoke();
    }
}

[ForyStruct]
internal sealed class InheritedLeaf : InheritedMiddle
{
    public override int VirtualValue { get; set; }

    [ForyField(6)]
    public string LeafValue { get; set; } = string.Empty;

    [ForyField(8)]
    public new int HiddenValue;
}

[ForyStruct]
internal abstract class EvolutionBaseV1
{
    [ForyField(1)]
    public int BaseValue { get; set; }
}

[ForyStruct]
internal sealed class EvolutionLeafV1 : EvolutionBaseV1
{
    [ForyField(3)]
    public int LeafValue { get; set; }
}

[ForyStruct]
internal abstract class EvolutionBaseV2
{
    [ForyField(1)]
    public int BaseValue { get; set; }

    [ForyField(2)]
    public string AddedBaseValue { get; set; } = "default";
}

[ForyStruct]
internal sealed class EvolutionLeafV2 : EvolutionBaseV2
{
    [ForyField(3)]
    public int LeafValue { get; set; }
}

[ForyStruct]
internal sealed class StorageProjection(int seed)
{
    public const int ConstantValue = 1;
    public static long StaticValue = 2;

    private readonly long _cache = 71;

    public StorageProjection()
        : this(0)
    {
    }

    [ForyField(1)]
    public int Value { get; set; }

    [ForyField(2)]
    public int Alias
    {
        get => Value;
        set => Value = value;
    }

    public (int Seed, long Cache) ReadStorageState()
    {
        return (seed, _cache);
    }
}

[ForyStruct(Target = typeof(ExternalGenericBase<int>), BaseOnly = true)]
internal abstract class ExternalGenericBaseSerializer
{
    [ForyField(1)]
    public abstract int Value { get; }

    [ForyField(
        Ignore = true,
        TargetDeclaringType = typeof(ExternalGenericBase<int>),
        TargetMemberName = "<Value>k__BackingField")]
    public abstract int ValueStorage { get; }
}

[ForyStruct]
internal sealed class ExternalGenericLeaf : ExternalGenericBase<int>
{
    [ForyField(2)]
    public string LeafValue { get; set; } = string.Empty;
}

public sealed class ClassInheritanceTests
{
    private static readonly long ObjectOwnerBytes =
        IntPtr.Size + IntPtr.Size + sizeof(int);

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public void FlattenedHierarchyRoundTrips(bool compatible, bool trackRef)
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Compatible(compatible)
            .TrackRef(trackRef)
            .Build()
            .Register<InheritedLeaf>(6401);
        InheritedLeaf value = new()
        {
            PublicValue = 13,
            VirtualValue = 17,
            LeafValue = "leaf",
        };
        value.SetRootState(7, "root");
        ((InheritedRoot)value).HiddenValue = 19;
        value.HiddenValue = 23;
        if (trackRef)
        {
            value.Self = value;
        }

        InheritedLeaf decoded = fory.Deserialize<InheritedLeaf>(fory.Serialize(value));

        Assert.Equal((7, "root", 41L), decoded.RootState());
        Assert.Equal(13, decoded.PublicValue);
        Assert.Equal(17, decoded.VirtualValue);
        Assert.Equal("leaf", decoded.LeafValue);
        Assert.Equal(19, ((InheritedRoot)decoded).HiddenValue);
        Assert.Equal(23, decoded.HiddenValue);
        Assert.Equal(trackRef, ReferenceEquals(decoded, decoded.Self));

        fory.Register<InheritedMiddle>(6408);
        InheritedMiddle middle = new()
        {
            PublicValue = 29,
            VirtualValue = 31,
        };
        middle.SetRootState(37, "middle");
        InheritedMiddle decodedMiddle =
            fory.Deserialize<InheritedMiddle>(fory.Serialize(middle));
        Assert.Equal((37, "middle", 41L), decodedMiddle.RootState());
        Assert.Equal(29, decodedMiddle.PublicValue);
        Assert.Equal(31, decodedMiddle.VirtualValue);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ReferencedHierarchiesRoundTrip(bool compatible)
    {
        ForyRuntime ordinaryFory = ForyRuntime.Builder()
            .Compatible(compatible)
            .Build()
            .Register<CrossAssemblyLeaf>(6402);
        CrossAssemblyLeaf ordinary = new()
        {
            PublicBaseValue = 47,
            MiddleValue = 53,
            LeafValue = "leaf",
        };
        ordinary.SetBaseState(59, "base", 61);
        CrossAssemblyLeaf decodedOrdinary =
            ordinaryFory.Deserialize<CrossAssemblyLeaf>(
                ordinaryFory.Serialize(ordinary));
        Assert.Equal((59, "base", 61, 37), decodedOrdinary.ReadBaseState());
        Assert.Equal(47, decodedOrdinary.PublicBaseValue);
        Assert.Equal(53, decodedOrdinary.MiddleValue);
        Assert.Equal("leaf", decodedOrdinary.LeafValue);

        ExternalLeaf external = new()
        {
            PublicValue = 31,
            MiddleValue = "middle",
            LeafValue = 43,
        };
        external.SetPrivateState(23, "vendor");
        ForyRuntime externalFory = ForyRuntime.Builder()
            .Compatible(compatible)
            .MaxGraphMemoryBytes(ObjectOwnerBytes + 32)
            .Build()
            .Register<ExternalLeaf>(6403);
        byte[] externalBytes = externalFory.Serialize(external);
        ExternalLeaf decodedExternal =
            externalFory.Deserialize<ExternalLeaf>(externalBytes);
        Assert.Equal((23L, "vendor", 29), decodedExternal.ReadPrivateState());
        Assert.Equal(31, decodedExternal.PublicValue);
        Assert.Equal("middle", decodedExternal.MiddleValue);
        Assert.Equal(43, decodedExternal.LeafValue);

        ForyRuntime tooSmall = ForyRuntime.Builder()
            .Compatible(compatible)
            .MaxGraphMemoryBytes(ObjectOwnerBytes + 31)
            .Build()
            .Register<ExternalLeaf>(6403);
        Assert.Throws<InvalidDataException>(
            () => tooSmall.Deserialize<ExternalLeaf>(externalBytes));
    }

    [Fact]
    public void WireAndStorageModelsAreFlattened()
    {
        int[] fieldIds = new TypeResolver()
            .GetTypeInfo<InheritedLeaf>()
            .TypeMetaFields(false)
            .Select(field => field.FieldId)
            .ToArray();
        Assert.Equal(
            new[] { 1, 3, 5, 7, 8, 2, 4, 6 },
            fieldIds);

        Assert.Equal(28, ProviderShallowBytes(typeof(InheritedRoot)));
        Assert.Equal(36, ProviderShallowBytes(typeof(InheritedMiddle)));
        Assert.Equal(48, SerializerShallowBytes(typeof(InheritedLeaf)));
        Assert.Equal(20, ProviderShallowBytes(typeof(SharedOrdinaryBase)));
        Assert.Equal(28, ProviderShallowBytes(typeof(CrossAssemblyMiddle)));
        Assert.Equal(32, SerializerShallowBytes(typeof(CrossAssemblyLeaf)));
        Assert.Equal(
            20,
            ProviderShallowBytes(
                typeof(ExternalPrivateDerived),
                typeof(SharedExternalHierarchy).Assembly));
        Assert.Equal(24, ProviderShallowBytes(typeof(ExternalMiddle)));
        Assert.Equal(32, SerializerShallowBytes(typeof(ExternalLeaf)));

        FieldInfo cache = typeof(ExternalPrivateBase).GetField(
            "_cache",
            BindingFlags.Instance |
            BindingFlags.NonPublic |
            BindingFlags.DeclaredOnly)!;
        Assert.Equal(typeof(int), cache.FieldType);
        Assert.False(cache.IsStatic);
    }

    [Fact]
    public void InheritedStorageUsesExactGraphLimit()
    {
        InheritedLeaf value = new()
        {
            PublicValue = 79,
            VirtualValue = 83,
            LeafValue = "budget",
        };
        value.SetRootState(89, "root");
        value.Self = value;
        ForyRuntime writer = ForyRuntime.Builder()
            .Compatible(false)
            .TrackRef(true)
            .Build()
            .Register<InheritedLeaf>(6404);
        byte[] bytes = writer.Serialize(value);
        long required = ObjectOwnerBytes + 48;

        ForyRuntime tooSmall = ForyRuntime.Builder()
            .Compatible(false)
            .TrackRef(true)
            .MaxGraphMemoryBytes(required - 1)
            .Build()
            .Register<InheritedLeaf>(6404);
        Assert.Throws<InvalidDataException>(
            () => tooSmall.Deserialize<InheritedLeaf>(bytes));

        ForyRuntime exact = ForyRuntime.Builder()
            .Compatible(false)
            .TrackRef(true)
            .MaxGraphMemoryBytes(required)
            .Build()
            .Register<InheritedLeaf>(6404);
        InheritedLeaf decoded = exact.Deserialize<InheritedLeaf>(bytes);
        Assert.Equal(79, decoded.PublicValue);
        Assert.Same(decoded, decoded.Self);
    }

    [Fact]
    public void CompatibleSchemaEvolvesBaseMembers()
    {
        ForyRuntime newWriter = ForyRuntime.Builder()
            .Compatible(true)
            .Build()
            .Register<EvolutionLeafV2>(6405);
        ForyRuntime oldReader = ForyRuntime.Builder()
            .Compatible(true)
            .Build()
            .Register<EvolutionLeafV1>(6405);
        EvolutionLeafV1 oldValue = oldReader.Deserialize<EvolutionLeafV1>(
            newWriter.Serialize(new EvolutionLeafV2
            {
                BaseValue = 167,
                AddedBaseValue = "added",
                LeafValue = 173,
            }));
        Assert.Equal(167, oldValue.BaseValue);
        Assert.Equal(173, oldValue.LeafValue);

        ForyRuntime oldWriter = ForyRuntime.Builder()
            .Compatible(true)
            .Build()
            .Register<EvolutionLeafV1>(6405);
        ForyRuntime newReader = ForyRuntime.Builder()
            .Compatible(true)
            .Build()
            .Register<EvolutionLeafV2>(6405);
        EvolutionLeafV2 newValue = newReader.Deserialize<EvolutionLeafV2>(
            oldWriter.Serialize(new EvolutionLeafV1
            {
                BaseValue = 179,
                LeafValue = 181,
            }));
        Assert.Equal(179, newValue.BaseValue);
        Assert.Equal("default", newValue.AddedBaseValue);
        Assert.Equal(181, newValue.LeafValue);
    }

    [Fact]
    public void ShallowStorageIsIndependentOfWireMembers()
    {
        Assert.Equal(16, SerializerShallowBytes(typeof(StorageProjection)));
        ForyRuntime writer = ForyRuntime.Builder()
            .Compatible(false)
            .Build()
            .Register<StorageProjection>(6406);
        byte[] bytes = writer.Serialize(new StorageProjection { Value = 107 });
        long required = ObjectOwnerBytes + 16;

        ForyRuntime tooSmall = ForyRuntime.Builder()
            .Compatible(false)
            .MaxGraphMemoryBytes(required - 1)
            .Build()
            .Register<StorageProjection>(6406);
        Assert.Throws<InvalidDataException>(
            () => tooSmall.Deserialize<StorageProjection>(bytes));

        ForyRuntime exact = ForyRuntime.Builder()
            .Compatible(false)
            .MaxGraphMemoryBytes(required)
            .Build()
            .Register<StorageProjection>(6406);
        StorageProjection decoded = exact.Deserialize<StorageProjection>(bytes);
        Assert.Equal(107, decoded.Value);
        Assert.Equal((0, 71L), decoded.ReadStorageState());
    }

    [Fact]
    public void ClosedGenericExternalBaseRoundTrips()
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Build()
            .Register<ExternalGenericLeaf>(6407);
        ExternalGenericLeaf decoded = fory.Deserialize<ExternalGenericLeaf>(
            fory.Serialize(new ExternalGenericLeaf
            {
                Value = 197,
                LeafValue = "generic",
            }));

        Assert.Equal(197, decoded.Value);
        Assert.Equal("generic", decoded.LeafValue);
        Assert.Equal(
            4,
            ProviderShallowBytes(typeof(ExternalGenericBase<int>), typeof(InheritedRoot).Assembly));
        Assert.Equal(8, SerializerShallowBytes(typeof(ExternalGenericLeaf)));
    }

    private static long ProviderShallowBytes(
        Type target,
        Assembly? providerAssembly = null)
    {
        Type provider = (providerAssembly ?? target.Assembly)
            .GetTypes()
            .Single(type =>
                type.GetCustomAttribute<ForyGeneratedHierarchyProviderAttribute>()
                    is { TargetType: var providerTarget } &&
                providerTarget == target);
        return (long)provider.GetField(
            "HierarchyShallowBytes",
            BindingFlags.Public | BindingFlags.Static)!.GetValue(null)!;
    }

    private static long SerializerShallowBytes(Type target)
    {
        Type serializer = target.Assembly.GetTypes().Single(type =>
            type.BaseType is { IsGenericType: true } baseType &&
            baseType.GetGenericTypeDefinition() == typeof(Serializer<>) &&
            baseType.GetGenericArguments()[0] == target &&
            type.GetField(
                "__ForyGraphMemoryBytes",
                BindingFlags.NonPublic | BindingFlags.Static) is not null);
        long graphBytes = (long)serializer.GetField(
            "__ForyGraphMemoryBytes",
            BindingFlags.NonPublic | BindingFlags.Static)!.GetValue(null)!;
        return graphBytes - ObjectOwnerBytes;
    }
}
