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
using Apache.Fory;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.Tests;

[ForyStruct]
public sealed class BudgetItem
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
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

public sealed class ContainerMemoryBudgetTests
{
    private static ForyRuntime NewFory(long maxContainerMemoryBytes = -1)
    {
        return ForyRuntime.Builder()
            .Compatible(false)
            .TrackRef(false)
            .MaxContainerMemoryBytes(maxContainerMemoryBytes)
            .Build()
            .Register<BudgetItem>(1001)
            .Register<BudgetSiblings>(1002)
            .Register<BudgetArrayHolder>(1003);
    }

    private static byte[] Serialize<T>(T value)
    {
        return NewFory().Serialize(value);
    }

    private static long ListBudget<T>(int count)
    {
        return count == 0
            ? ReadContext.ContainerFixedBytes
            : ReadContext.ContainerFixedBytes + ReadContext.ArrayHeaderBytes +
              (long)count * ReadContext.ElementBytes<T>();
    }

    private static long ArrayBudget<T>(int count)
    {
        return ReadContext.ArrayHeaderBytes + (long)count * ReadContext.ElementBytes<T>();
    }

    private static long MapBudget<TKey, TValue>(int count)
    {
        return count == 0
            ? ReadContext.ContainerFixedBytes
            : ReadContext.ContainerFixedBytes + ReadContext.ArrayHeaderBytes * 2 +
              (long)count * (ReadContext.ElementBytes<TKey>() + ReadContext.ElementBytes<TValue>() +
                             ReadContext.MapEntryOverheadBytes + ReadContext.ReferenceBytes);
    }

    [Fact]
    public void KnownLengthAutoBudgetRejectsLargeNestedEmpties()
    {
        const int count = 3000;
        List<List<string>> value = Enumerable.Range(0, count).Select(_ => new List<string>()).ToList();
        byte[] bytes = Serialize(value);
        long autoLimit = bytes.LongLength * 8 + ReadContext.KnownContainerBudgetSlackBytes;
        long required = ListBudget<List<string>>(count) + count * ListBudget<string>(0);
        Assert.True(required > autoLimit);

        Assert.Throws<InvalidDataException>(() => NewFory().Deserialize<List<List<string>>>(bytes));

        List<List<string>> result = NewFory(required).Deserialize<List<List<string>>>(bytes);
        Assert.Equal(count, result.Count);
    }

    [Fact]
    public void ReadOnlySequenceUsesKnownLengthAutoBudget()
    {
        const int count = 3000;
        List<List<string>> value = Enumerable.Range(0, count).Select(_ => new List<string>()).ToList();
        byte[] bytes = Serialize(value);
        ReadOnlySequence<byte> sequence = new(bytes);

        Assert.Throws<InvalidDataException>(() => NewFory().Deserialize<List<List<string>>>(ref sequence));
    }

    [Fact]
    public void ExplicitConfigOverridesAutoBudget()
    {
        List<BudgetItem> value = Enumerable.Range(0, 8).Select(i => new BudgetItem { Id = i }).ToList();
        byte[] bytes = Serialize(value);
        long required = ListBudget<BudgetItem>(value.Count);

        Assert.Throws<InvalidDataException>(() => NewFory(required - 1).Deserialize<List<BudgetItem>>(bytes));
        List<BudgetItem> result = NewFory(required).Deserialize<List<BudgetItem>>(bytes);
        Assert.Equal(value.Count, result.Count);
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
        long oneList = ListBudget<BudgetItem>(16);

        Assert.Throws<InvalidDataException>(() => NewFory(oneList).Deserialize<BudgetSiblings>(bytes));
        BudgetSiblings result = NewFory(oneList * 2).Deserialize<BudgetSiblings>(bytes);
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
        long holderRequired = ListBudget<BudgetItem>(4) + ArrayBudget<BudgetItem>(4);
        Assert.Throws<InvalidDataException>(() => NewFory(holderRequired - 1).Deserialize<BudgetArrayHolder>(holderBytes));
        Assert.Equal(4, NewFory(holderRequired).Deserialize<BudgetArrayHolder>(holderBytes).Values.Length);

        List<int> ints = [1, 2, 3, 4];
        byte[] intBytes = Serialize(ints);
        long listRequired = ListBudget<int>(ints.Count);
        Assert.Throws<InvalidDataException>(() => NewFory(listRequired - 1).Deserialize<List<int>>(intBytes));
        Assert.Equal(ints, NewFory(listRequired).Deserialize<List<int>>(intBytes));
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
