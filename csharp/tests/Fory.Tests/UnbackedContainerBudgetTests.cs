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

using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.Tests;

public sealed class EmptyStub
{
}

public sealed class EmptyStubSerializer : Serializer<EmptyStub>
{
    public override void WriteData(WriteContext context, in EmptyStub value, bool hasGenerics)
    {
    }

    public override EmptyStub ReadData(ReadContext context)
    {
        return new EmptyStub();
    }
}

public sealed class SparseStub
{
}

public sealed class SparseStubSerializer : Serializer<SparseStub>
{
    private int _writeIndex;
    private int _readIndex;

    public override void WriteData(WriteContext context, in SparseStub value, bool hasGenerics)
    {
        if ((_writeIndex++ & 1) == 0)
        {
            context.Writer.WriteUInt8(0);
        }
    }

    public override SparseStub ReadData(ReadContext context)
    {
        if ((_readIndex++ & 1) == 0)
        {
            _ = context.Reader.ReadUInt8();
        }

        return new SparseStub();
    }
}

public sealed class UnbackedContainerBudgetTests
{
    private const uint EmptyStubTypeId = 1050;
    private const uint SparseStubTypeId = 1051;
    private const uint EvolvingTypeId = 1052;

    [Fact]
    public void ConfigRange()
    {
        Assert.Equal(8192, ForyRuntime.Builder().Build().Config.MaxUnbackedContainerItems);
        Assert.Throws<ArgumentOutOfRangeException>(
            () => ForyRuntime.Builder().MaxUnbackedContainerItems(-1));
        Assert.Equal(
            0,
            ForyRuntime.Builder().MaxUnbackedContainerItems(0).Build()
                .Config.MaxUnbackedContainerItems);
    }

    [Fact]
    public void CustomCollectionBudget()
    {
        byte[] payload = NewFory(8192).Serialize(
            Enumerable.Range(0, 1025).Select(_ => new EmptyStub()).ToList());

        Assert.ThrowsAny<ForyException>(
            () => NewFory(1024).Deserialize<List<EmptyStub>>(payload));
        Assert.Equal(
            1025,
            NewFory(1025).Deserialize<List<EmptyStub>>(payload).Count);
    }

    [Fact]
    public void GeneratedEmptyStructBudget()
    {
        ForyRuntime writer = NewGeneratedFory(8192);
        byte[] payload = writer.Serialize(
            Enumerable.Range(0, 3).Select(_ => new BudgetEmpty()).ToList());

        Assert.ThrowsAny<ForyException>(
            () => NewGeneratedFory(2).Deserialize<List<BudgetEmpty>>(payload));
        Assert.Equal(
            3,
            NewGeneratedFory(3).Deserialize<List<BudgetEmpty>>(payload).Count);
    }

    [Fact]
    public void GeneratedProgressFacts()
    {
        TypeResolver resolver = new();

        Assert.False(resolver.GetTypeInfo<BudgetEmpty>().ReadDataAlwaysAdvances);
        Assert.True(resolver.GetTypeInfo<BudgetItem>().ReadDataAlwaysAdvances);
        Assert.True(resolver.GetTypeInfo<List<BudgetEmpty>>().ReadDataAlwaysAdvances);
    }

    [Fact]
    public void RemoteTypeMetaProgressFacts()
    {
        Assert.True(RemoteTypeMeta(new TypeMetaFieldType((uint)TypeId.Int32, false))
            .ReadDataAlwaysAdvances);
        Assert.True(RemoteTypeMeta(new TypeMetaFieldType((uint)TypeId.List, false))
            .ReadDataAlwaysAdvances);
        Assert.True(RemoteTypeMeta(new TypeMetaFieldType((uint)TypeId.Struct, true))
            .ReadDataAlwaysAdvances);
        Assert.False(RemoteTypeMeta().ReadDataAlwaysAdvances);
        Assert.False(RemoteTypeMeta(new TypeMetaFieldType((uint)TypeId.None, false))
            .ReadDataAlwaysAdvances);
        Assert.False(RemoteTypeMeta(new TypeMetaFieldType((uint)TypeId.Struct, false))
            .ReadDataAlwaysAdvances);
    }

    [Fact]
    public void CompatibleCollectionUsesRemoteProgress()
    {
        ForyRuntime positiveWriter = NewCompatible<BudgetItem>(EvolvingTypeId, 0);
        ForyRuntime emptyReader = NewCompatible<BudgetEmpty>(EvolvingTypeId, 0);
        byte[] positive = positiveWriter.Serialize(
            Enumerable.Range(0, 3).Select(i => new BudgetItem { Id = i }).ToList());
        Assert.Equal(3, emptyReader.Deserialize<List<BudgetEmpty>>(positive).Count);

        ForyRuntime emptyWriter = NewCompatible<BudgetEmpty>(EvolvingTypeId, 0);
        ForyRuntime positiveReader = NewCompatible<BudgetItem>(EvolvingTypeId, 0);
        byte[] empty = emptyWriter.Serialize(
            Enumerable.Range(0, 3).Select(_ => new BudgetEmpty()).ToList());
        Assert.ThrowsAny<ForyException>(
            () => positiveReader.Deserialize<List<BudgetItem>>(empty));
    }

    [Fact]
    public void CompatibleMapUsesRemoteProgress()
    {
        ForyRuntime positiveWriter = NewCompatible<BudgetItem>(EvolvingTypeId, 0);
        ForyRuntime emptyReader = NewCompatible<BudgetEmpty>(EvolvingTypeId, 0);
        Dictionary<BudgetItem, BudgetItem> positive = Enumerable.Range(0, 3).ToDictionary(
            i => new BudgetItem { Id = i },
            i => new BudgetItem { Id = i + 10 });
        Assert.Equal(
            3,
            emptyReader.Deserialize<Dictionary<BudgetEmpty, BudgetEmpty>>(
                positiveWriter.Serialize(positive)).Count);
        Assert.Equal(
            3,
            emptyReader.Deserialize<NullableKeyDictionary<BudgetEmpty, BudgetEmpty>>(
                positiveWriter.Serialize(
                    new NullableKeyDictionary<BudgetItem, BudgetItem>(positive))).Count);

        ForyRuntime emptyWriter = NewCompatible<BudgetEmpty>(EvolvingTypeId, 0);
        ForyRuntime positiveReader = NewCompatible<BudgetItem>(EvolvingTypeId, 0);
        Dictionary<BudgetEmpty, BudgetEmpty> empty = Enumerable.Range(0, 3).ToDictionary(
            _ => new BudgetEmpty(),
            _ => new BudgetEmpty());
        byte[] encoded = emptyWriter.Serialize(empty);
        Assert.ThrowsAny<ForyException>(
            () => positiveReader.Deserialize<Dictionary<BudgetItem, BudgetItem>>(encoded));
        byte[] nullableEncoded = emptyWriter.Serialize(
            new NullableKeyDictionary<BudgetEmpty, BudgetEmpty>(empty));
        Assert.ThrowsAny<ForyException>(
            () => positiveReader.Deserialize<NullableKeyDictionary<BudgetItem, BudgetItem>>(
                nullableEncoded));
    }

    [Fact]
    public void NestedCollectionsShareBudget()
    {
        List<List<EmptyStub>> value =
        [
            [new EmptyStub(), new EmptyStub(), new EmptyStub()],
            [new EmptyStub(), new EmptyStub(), new EmptyStub()],
        ];
        byte[] payload = NewFory(8192).Serialize(value);

        Assert.ThrowsAny<ForyException>(
            () => NewFory(5).Deserialize<List<List<EmptyStub>>>(payload));
        Assert.Equal(
            2,
            NewFory(6).Deserialize<List<List<EmptyStub>>>(payload).Count);
    }

    [Fact]
    public void PartialProgressOffsetsItems()
    {
        byte[] payload = NewSparseFory(8192).Serialize(
            Enumerable.Range(0, 2048).Select(_ => new SparseStub()).ToList());

        Assert.ThrowsAny<ForyException>(
            () => NewSparseFory(1023).Deserialize<List<SparseStub>>(payload));
        Assert.Equal(
            2048,
            NewSparseFory(1024).Deserialize<List<SparseStub>>(payload).Count);
    }

    [Fact]
    public void MapChunksShareBudget()
    {
        Dictionary<EmptyStub, EmptyStub> value = new()
        {
            [new EmptyStub()] = new EmptyStub(),
            [new EmptyStub()] = new EmptyStub(),
            [new EmptyStub()] = new EmptyStub(),
        };
        byte[] payload = NewFory(8192).Serialize(value);

        Assert.Throws<InvalidDataException>(
            () => NewFory(2).Deserialize<Dictionary<EmptyStub, EmptyStub>>(payload));
        Assert.Equal(
            3,
            NewFory(3).Deserialize<Dictionary<EmptyStub, EmptyStub>>(payload).Count);
    }

    [Fact]
    public void FailedRootResetsBudget()
    {
        ForyRuntime writer = NewFory(8192);
        byte[] rejected = writer.Serialize(
            new List<EmptyStub> { new(), new() });
        byte[] accepted = writer.Serialize(
            new List<EmptyStub> { new() });
        ForyRuntime reader = NewFory(1);

        Assert.ThrowsAny<ForyException>(
            () => reader.Deserialize<List<EmptyStub>>(rejected));
        Assert.Single(reader.Deserialize<List<EmptyStub>>(accepted));
    }

    [Fact]
    public void PositiveBodiesDoNotSpendBudget()
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .MaxUnbackedContainerItems(0)
            .Build();
        List<int> value = Enumerable.Range(0, 10_000).ToList();

        Assert.Equal(value, fory.Deserialize<List<int>>(fory.Serialize(value)));
    }

    private static ForyRuntime NewFory(long maxUnbackedContainerItems)
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Compatible(true)
            .MaxUnbackedContainerItems(maxUnbackedContainerItems)
            .Build();
        fory.Register<EmptyStub, EmptyStubSerializer>(EmptyStubTypeId);
        return fory;
    }

    private static ForyRuntime NewGeneratedFory(long maxUnbackedContainerItems)
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Compatible(true)
            .MaxUnbackedContainerItems(maxUnbackedContainerItems)
            .Build();
        fory.Register<BudgetEmpty>(EmptyStubTypeId);
        return fory;
    }

    private static ForyRuntime NewSparseFory(long maxUnbackedContainerItems)
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Compatible(true)
            .MaxUnbackedContainerItems(maxUnbackedContainerItems)
            .Build();
        fory.Register<SparseStub, SparseStubSerializer>(SparseStubTypeId);
        return fory;
    }

    private static ForyRuntime NewCompatible<T>(uint typeId, long maxUnbackedContainerItems)
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Compatible(true)
            .MaxUnbackedContainerItems(maxUnbackedContainerItems)
            .Build();
        fory.Register<T>(typeId);
        return fory;
    }

    private static TypeMeta RemoteTypeMeta(params TypeMetaFieldType[] fieldTypes)
    {
        TypeMetaFieldInfo[] fields = new TypeMetaFieldInfo[fieldTypes.Length];
        for (int i = 0; i < fieldTypes.Length; i++)
        {
            fields[i] = new TypeMetaFieldInfo(i, $"field{i}", fieldTypes[i]);
        }

        return new TypeMeta(
            (uint)TypeId.CompatibleStruct,
            EvolvingTypeId,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            false,
            fields);
    }
}
