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
using System.Runtime.CompilerServices;
using Apache.Fory;
using ForyRuntime = Apache.Fory.Fory;
using S = Apache.Fory.Schema.Types;

namespace Apache.Fory.Tests;

[ForyStruct]
public sealed class BudgetItem
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
}

[ForyStruct]
public sealed class BudgetEmpty
{
}

[ForyStruct]
public sealed class BudgetSiblings
{
    public List<BudgetItem> Left { get; set; } = [];
    public List<BudgetItem> Right { get; set; } = [];
}

[ForyStruct]
public sealed class BudgetArrayHolder
{
    public BudgetItem[] Values { get; set; } = [];
}

[ForyStruct]
public struct BudgetValue
{
    public int Id { get; set; }
}

[ForyStruct]
public sealed class GeneratedSchemaListBudget
{
    [ForyField(Type = typeof(S.List<S.Int32>))]
    public List<int> Values { get; set; } = [];
}

[ForyStruct]
public sealed class GeneratedPackedListBudget
{
    [ForyField(Type = typeof(S.Array<S.Int32>))]
    public List<int> Values { get; set; } = [];
}

[ForyStruct]
public sealed class GeneratedSchemaMapBudget
{
    [ForyField(Type = typeof(S.Map<S.Int32, S.Int32>))]
    public Dictionary<int, int> Values { get; set; } = [];
}

public sealed class GraphMemoryBudgetTests
{
    private const int ReferenceBytes = 4;
    private const int ObjectBytes = 1;
    private const long BudgetEmptyBytes = ObjectBytes;
    private const long BudgetItemBytes = ObjectBytes + 4 + ReferenceBytes;
    private const long BudgetSiblingsBytes = ObjectBytes + ReferenceBytes + ReferenceBytes;
    private const long BudgetArrayHolderBytes = ObjectBytes + ReferenceBytes;
    private const long GeneratedGraphHolderBytes = ObjectBytes + ReferenceBytes;
    private const long BudgetValueBytes = 4;
    private const long DefaultGraphMemoryBytes = 128L * 1024 * 1024;

    private static int ElementBytes<T>() => typeof(T).IsValueType ? Unsafe.SizeOf<T>() : ReferenceBytes;

    private static ForyRuntime NewFory(long maxGraphMemoryBytes = DefaultGraphMemoryBytes)
    {
        return ForyRuntime.Builder()
            .Compatible(false)
            .TrackRef(false)
            .MaxGraphMemoryBytes(maxGraphMemoryBytes)
            .Build()
            .Register<BudgetItem>(1001)
            .Register<BudgetEmpty>(1002)
            .Register<BudgetSiblings>(1003)
            .Register<BudgetArrayHolder>(1004)
            .Register<BudgetValue>(1005)
            .Register<GeneratedSchemaListBudget>(1006)
            .Register<GeneratedPackedListBudget>(1007)
            .Register<GeneratedSchemaMapBudget>(1008);
    }

    private static byte[] Serialize<T>(T value)
    {
        return NewFory().Serialize(value);
    }

    private static long ListBudget<T>(int count)
    {
        return ObjectBytes + (long)count * ElementBytes<T>();
    }

    private static long ArrayBudget<T>(int count)
    {
        return ObjectBytes + (long)count * ElementBytes<T>();
    }

    private static long MapBudget<TKey, TValue>(int count)
    {
        return ObjectBytes + (long)count * (ElementBytes<TKey>() + ElementBytes<TValue>());
    }

    [Fact]
    public void DefaultFixedBudgetAndDisable()
    {
        Assert.Equal(DefaultGraphMemoryBytes, NewFory().Config.MaxGraphMemoryBytes);
        Assert.Equal(0, NewFory(0).Config.MaxGraphMemoryBytes);
        Assert.Equal(-2, NewFory(-2).Config.MaxGraphMemoryBytes);

        ReadContext context = new(new ByteReader([]), new TypeResolver(), NewFory().Config);
        context.InitGraphBudget();
        context.ReserveGraphMemory(DefaultGraphMemoryBytes);
        Assert.Throws<InvalidDataException>(() => context.ReserveGraphMemory(ReferenceBytes));

        ReadContext disabled = new(new ByteReader([]), new TypeResolver(), NewFory(0).Config);
        disabled.InitGraphBudget();
        disabled.ReserveGraphMemory(long.MaxValue);
    }

    [Fact]
    public void ReadOnlySequenceUsesSameBudget()
    {
        const int count = 6;
        List<List<string>> value = Enumerable.Range(0, count).Select(_ => new List<string>()).ToList();
        byte[] bytes = Serialize(value);
        ReadOnlySequence<byte> sequence = new(bytes);

        Assert.Equal(count, NewFory().Deserialize<List<List<string>>>(ref sequence).Count);
    }

    [Fact]
    public void ExplicitConfigOverridesDefault()
    {
        List<BudgetItem> value = Enumerable.Range(0, 8).Select(i => new BudgetItem { Id = i }).ToList();
        byte[] bytes = Serialize(value);
        long required = ListBudget<BudgetItem>(value.Count) + value.Count * BudgetItemBytes;

        Assert.Throws<InvalidDataException>(() => NewFory(required - 1).Deserialize<List<BudgetItem>>(bytes));
        List<BudgetItem> result = NewFory(required).Deserialize<List<BudgetItem>>(bytes);
        Assert.Equal(value.Count, result.Count);
    }

    [Fact]
    public void EmptyObjectOwnerIsCharged()
    {
        List<BudgetEmpty> value = [new BudgetEmpty()];
        byte[] bytes = Serialize(value);
        long required = ListBudget<BudgetEmpty>(value.Count) + value.Count * BudgetEmptyBytes;

        Assert.Throws<InvalidDataException>(() => NewFory(required - 1).Deserialize<List<BudgetEmpty>>(bytes));
        Assert.Single(NewFory(required).Deserialize<List<BudgetEmpty>>(bytes));
    }

    [Fact]
    public void SiblingContainersShareOneBudget()
    {
        BudgetSiblings value = new()
        {
            Left = Enumerable.Range(0, 16).Select(i => new BudgetItem { Id = i }).ToList(),
            Right = Enumerable.Range(0, 16).Select(i => new BudgetItem { Id = i }).ToList(),
        };
        byte[] bytes = Serialize(value);
        long oneList = ListBudget<BudgetItem>(16) + 16 * BudgetItemBytes;
        long required = BudgetSiblingsBytes + oneList * 2;

        Assert.Throws<InvalidDataException>(() => NewFory(required - 1).Deserialize<BudgetSiblings>(bytes));
        BudgetSiblings result = NewFory(required).Deserialize<BudgetSiblings>(bytes);
        Assert.Equal(16, result.Left.Count);
        Assert.Equal(16, result.Right.Count);
    }

    [Fact]
    public void MapBudgetIsCharged()
    {
        Dictionary<string, int> value = new() { ["a"] = 1, ["b"] = 2, ["c"] = 3 };
        byte[] bytes = Serialize(value);
        long required = MapBudget<string, int>(value.Count);

        Assert.Throws<InvalidDataException>(() => NewFory(required - 1).Deserialize<Dictionary<string, int>>(bytes));
        Dictionary<string, int> result = NewFory(required).Deserialize<Dictionary<string, int>>(bytes);
        Assert.Equal(value, result);
    }

    [Fact]
    public void ReferenceArrayAndInlineValueListAreCharged()
    {
        BudgetArrayHolder holder = new()
        {
            Values = Enumerable.Range(0, 4).Select(i => new BudgetItem { Id = i }).ToArray(),
        };
        byte[] holderBytes = Serialize(holder);
        long holderRequired =
            BudgetArrayHolderBytes + ArrayBudget<BudgetItem>(4) + holder.Values.Length * BudgetItemBytes;
        Assert.Throws<InvalidDataException>(() => NewFory(holderRequired - 1).Deserialize<BudgetArrayHolder>(holderBytes));
        Assert.Equal(4, NewFory(holderRequired).Deserialize<BudgetArrayHolder>(holderBytes).Values.Length);

        List<int> ints = [1, 2, 3, 4];
        byte[] intBytes = Serialize(ints);
        long listRequired = ListBudget<int>(ints.Count);
        Assert.Throws<InvalidDataException>(() => NewFory(listRequired - 1).Deserialize<List<int>>(intBytes));
        Assert.Equal(ints, NewFory(listRequired).Deserialize<List<int>>(intBytes));
    }

    [Fact]
    public void ValueStructOwnerIsChargedByHolder()
    {
        BudgetValue value = new() { Id = 7 };
        byte[] valueBytes = Serialize(value);
        Assert.Throws<InvalidDataException>(() => NewFory(BudgetValueBytes - 1).Deserialize<BudgetValue>(valueBytes));
        Assert.Equal(value.Id, NewFory(BudgetValueBytes).Deserialize<BudgetValue>(valueBytes).Id);

        List<BudgetValue> values = Enumerable.Range(0, 4).Select(i => new BudgetValue { Id = i }).ToList();
        byte[] listBytes = Serialize(values);
        long listRequired = ListBudget<BudgetValue>(values.Count);
        Assert.Throws<InvalidDataException>(() => NewFory(listRequired - 1).Deserialize<List<BudgetValue>>(listBytes));
        Assert.Equal(values.Select(v => v.Id), NewFory(listRequired).Deserialize<List<BudgetValue>>(listBytes).Select(v => v.Id));
    }

    [Fact]
    public void GeneratedSchemaContainersAreCharged()
    {
        GeneratedSchemaListBudget list = new() { Values = [1, 2, 3, 4, 5, 6] };
        byte[] listBytes = Serialize(list);
        long listRequired = GeneratedGraphHolderBytes + ListBudget<int>(list.Values.Count);
        Assert.Throws<InvalidDataException>(() => NewFory(listRequired - 1).Deserialize<GeneratedSchemaListBudget>(listBytes));
        Assert.Equal(list.Values, NewFory(listRequired).Deserialize<GeneratedSchemaListBudget>(listBytes).Values);

        GeneratedPackedListBudget packed = new() { Values = [1, 2, 3, 4, 5, 6] };
        byte[] packedBytes = Serialize(packed);
        long packedRequired = GeneratedGraphHolderBytes + ListBudget<int>(packed.Values.Count);
        Assert.Throws<InvalidDataException>(() => NewFory(packedRequired - 1).Deserialize<GeneratedPackedListBudget>(packedBytes));
        Assert.Equal(packed.Values, NewFory(packedRequired).Deserialize<GeneratedPackedListBudget>(packedBytes).Values);

        GeneratedSchemaMapBudget map = new()
        {
            Values = new Dictionary<int, int> { [1] = 1, [2] = 2, [3] = 3 },
        };
        byte[] mapBytes = Serialize(map);
        long mapRequired = GeneratedGraphHolderBytes + MapBudget<int, int>(map.Values.Count);
        Assert.Throws<InvalidDataException>(() => NewFory(mapRequired - 1).Deserialize<GeneratedSchemaMapBudget>(mapBytes));
        Assert.Equal(map.Values, NewFory(mapRequired).Deserialize<GeneratedSchemaMapBudget>(mapBytes).Values);
    }

    [Fact]
    public void DenseStringBinaryAndPrimitiveArraysAreSkipped()
    {
        Assert.Equal("budget", NewFory(1).Deserialize<string>(Serialize("budget")));
        Assert.Equal(new byte[] { 1, 2, 3 }, NewFory(1).Deserialize<byte[]>(Serialize(new byte[] { 1, 2, 3 })));
        Assert.Equal(new[] { 1, 2, 3 }, NewFory(1).Deserialize<int[]>(Serialize(new[] { 1, 2, 3 })));
    }

    [Fact]
    public void ByteAvailabilityCheckStillRejectsLargeLength()
    {
        byte[] bytes = [64, 0];
        ReadContext context = new(new ByteReader(bytes), new TypeResolver(), NewFory().Config);

        Assert.Throws<OutOfBoundsException>(() => new ListSerializer<string>().ReadData(context));
    }
}
