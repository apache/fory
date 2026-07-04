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
using System.Collections.Immutable;
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
public sealed class BudgetSelfNode
{
    public int Id { get; set; }
    public BudgetSelfNode? Next { get; set; }
    public List<BudgetSelfNode> Children { get; set; } = [];
}

[ForyStruct]
public struct BudgetValue
{
    public int Id { get; set; }
}

[ForyStruct]
public sealed class BudgetValueHolder
{
    public BudgetValue Value { get; set; }
}

[ForyStruct]
public sealed class BudgetValueCompatWriter
{
    public BudgetValue Value { get; set; }
    public int Extra { get; set; }
}

[ForyStruct]
public sealed class BudgetValueCompatReader
{
    public BudgetValue Value { get; set; }
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

[ForyStruct]
public sealed class CompatibleBudgetList
{
    [ForyField(Type = typeof(S.List<S.Int32>))]
    public List<int> Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleBudgetArray
{
    [ForyField(Type = typeof(S.Array<S.Int32>))]
    public int[] Values { get; set; } = [];
}

public sealed class GraphMemoryBudgetTests
{
    private const int ReferenceBytes = 4;
    private static readonly int ObjectHeaderBytes = IntPtr.Size + IntPtr.Size;
    private static readonly int ObjectOwnerBytes = ObjectHeaderBytes + 4;
    private static readonly int ArrayOwnerBytes = ObjectHeaderBytes + sizeof(int);
    private static readonly int ListOwnerBytes = ObjectHeaderBytes + ReferenceBytes + 2 * sizeof(int);
    private static readonly int HashSetOwnerBytes = ObjectHeaderBytes + 3 * ReferenceBytes + 4 * sizeof(int);
    private static readonly int SortedSetOwnerBytes = ObjectHeaderBytes + 3 * ReferenceBytes + sizeof(int);
    private static readonly int ImmutableHashSetOwnerBytes = ObjectHeaderBytes + ReferenceBytes;
    private static readonly int LinkedListOwnerBytes = ObjectHeaderBytes + 3 * ReferenceBytes + 2 * sizeof(int);
    private static readonly int QueueOwnerBytes = ObjectHeaderBytes + ReferenceBytes + 3 * sizeof(int);
    private static readonly int StackOwnerBytes = ObjectHeaderBytes + ReferenceBytes + 2 * sizeof(int);
    private static readonly int DictionaryOwnerBytes = ObjectHeaderBytes + 4 * ReferenceBytes + 4 * sizeof(int);
    private static readonly int NullableKeyDictionaryOwnerBytes =
        ObjectHeaderBytes + 4 * ReferenceBytes + sizeof(bool);
    private static readonly long BudgetEmptyBytes = ObjectOwnerBytes;
    private static readonly long BudgetItemBytes = ObjectOwnerBytes + 4 + ReferenceBytes;
    private static readonly long BudgetSiblingsBytes = ObjectOwnerBytes + ReferenceBytes + ReferenceBytes;
    private static readonly long BudgetArrayHolderBytes = ObjectOwnerBytes + ReferenceBytes;
    private static readonly long BudgetSelfNodeBytes = ObjectOwnerBytes + 4 + ReferenceBytes + ReferenceBytes;
    private static readonly long GeneratedGraphHolderBytes = ObjectOwnerBytes + ReferenceBytes;
    private const long BudgetValueBytes = 4;
    private static readonly long BudgetValueHolderBytes = ObjectOwnerBytes + BudgetValueBytes;
    private const long DefaultGraphMemoryBytes = 128L * 1024 * 1024;

    private static int ElementBytes<T>() => typeof(T).IsValueType ? Unsafe.SizeOf<T>() : ReferenceBytes;

    private static ForyRuntime NewFory(
        long maxGraphMemoryBytes = DefaultGraphMemoryBytes,
        bool trackRef = false)
    {
        return ForyRuntime.Builder()
            .Compatible(false)
            .TrackRef(trackRef)
            .MaxGraphMemoryBytes(maxGraphMemoryBytes)
            .Build()
            .Register<BudgetItem>(1001)
            .Register<BudgetEmpty>(1002)
            .Register<BudgetSiblings>(1003)
            .Register<BudgetArrayHolder>(1004)
            .Register<BudgetValue>(1005)
            .Register<GeneratedSchemaListBudget>(1006)
            .Register<GeneratedPackedListBudget>(1007)
            .Register<GeneratedSchemaMapBudget>(1008)
            .Register<BudgetValueHolder>(1009)
            .Register<BudgetSelfNode>(1012);
    }

    private static byte[] Serialize<T>(T value)
    {
        return NewFory().Serialize(value);
    }

    private static long ListBudget<T>(int count)
    {
        return ListOwnerBytes + (long)count * ElementBytes<T>();
    }

    private static long CollectionBudget<T>(int ownerBytes, int count)
    {
        return ownerBytes + (long)count * ElementBytes<T>();
    }

    private static long ArrayBudget<T>(int count)
    {
        return ArrayOwnerBytes + (long)count * ElementBytes<T>();
    }

    private static long MapBudget<TKey, TValue>(int count)
    {
        return DictionaryOwnerBytes + (long)count * (ElementBytes<TKey>() + ElementBytes<TValue>());
    }

    private static long NullableKeyMapBudget<TKey, TValue>(int count)
    {
        return NullableKeyDictionaryOwnerBytes
            + MapBudget<TKey, TValue>(count);
    }

    [Fact]
    public void DefaultFixedBudgetAndValidation()
    {
        Assert.Equal(DefaultGraphMemoryBytes, NewFory().Config.MaxGraphMemoryBytes);
        Assert.Throws<ArgumentOutOfRangeException>(() => NewFory(0));
        Assert.Throws<ArgumentOutOfRangeException>(() => NewFory(-2));

        List<List<string>> value = Enumerable.Range(0, 3).Select(_ => new List<string>()).ToList();
        Assert.Equal(value.Count, NewFory().Deserialize<List<List<string>>>(Serialize(value)).Count);
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
    public void GeneratedSelfReferenceBudget()
    {
        BudgetSelfNode value = new() { Id = 7 };
        value.Next = value;
        value.Children.Add(value);

        byte[] bytes = NewFory(trackRef: true).Serialize(value);
        long required = BudgetSelfNodeBytes + ListBudget<BudgetSelfNode>(1);

        Assert.Throws<InvalidDataException>(
            () => NewFory(required - 1, trackRef: true).Deserialize<BudgetSelfNode>(bytes));
        BudgetSelfNode result = NewFory(required, trackRef: true).Deserialize<BudgetSelfNode>(bytes);
        Assert.Same(result, result.Next);
        Assert.Single(result.Children);
        Assert.Same(result, result.Children[0]);
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
    public void DynamicMapReturnOwnerIsCharged()
    {
        Dictionary<object, object?> value = new() { ["a"] = 1, ["b"] = "two" };
        byte[] bytes = NewFory().Serialize<object>(value);
        long required = NullableKeyMapBudget<object, object?>(value.Count) + MapBudget<object, object?>(value.Count);

        Assert.Throws<InvalidDataException>(() => NewFory(required - 1).Deserialize<object>(bytes));
        Dictionary<object, object?> result = Assert.IsType<Dictionary<object, object?>>(
            NewFory(required).Deserialize<object>(bytes));
        Assert.Equal(value.Count, result.Count);
    }

    [Fact]
    public void ArrayAndInlineListBudget()
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
        Assert.Equal(value.Id, NewFory(1).Deserialize<BudgetValue>(valueBytes).Id);

        List<BudgetValue> values = Enumerable.Range(0, 4).Select(i => new BudgetValue { Id = i }).ToList();
        byte[] listBytes = Serialize(values);
        long listRequired = ListBudget<BudgetValue>(values.Count);
        Assert.Throws<InvalidDataException>(() => NewFory(listRequired - 1).Deserialize<List<BudgetValue>>(listBytes));
        Assert.Equal(values.Select(v => v.Id), NewFory(listRequired).Deserialize<List<BudgetValue>>(listBytes).Select(v => v.Id));

        BudgetValueHolder holder = new() { Value = new BudgetValue { Id = 11 } };
        byte[] holderBytes = Serialize(holder);
        Assert.Throws<InvalidDataException>(() => NewFory(BudgetValueHolderBytes - 1).Deserialize<BudgetValueHolder>(holderBytes));
        Assert.Equal(holder.Value.Id, NewFory(BudgetValueHolderBytes).Deserialize<BudgetValueHolder>(holderBytes).Value.Id);
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
    public void ConversionCollectionsAreChargedOnce()
    {
        Check(CollectionBudget<int>(HashSetOwnerBytes, 3), new HashSet<int> { 1, 2, 3 }, v => v.SetEquals([1, 2, 3]));
        Check(CollectionBudget<int>(SortedSetOwnerBytes, 3), new SortedSet<int> { 1, 2, 3 }, v => v.SetEquals([1, 2, 3]));
        Check(CollectionBudget<int>(ImmutableHashSetOwnerBytes, 3), ImmutableHashSet.Create(1, 2, 3), v => v.SetEquals([1, 2, 3]));
        Check(CollectionBudget<int>(LinkedListOwnerBytes, 3), new LinkedList<int>([1, 2, 3]), v => v.SequenceEqual([1, 2, 3]));

        Queue<int> queue = new();
        queue.Enqueue(1);
        queue.Enqueue(2);
        queue.Enqueue(3);
        Check(CollectionBudget<int>(QueueOwnerBytes, 3), queue, v => v.SequenceEqual([1, 2, 3]));

        Stack<int> stack = new();
        stack.Push(1);
        stack.Push(2);
        stack.Push(3);
        Check(CollectionBudget<int>(StackOwnerBytes, 3), stack, v => v.SequenceEqual([3, 2, 1]));

        void Check<T>(long required, T value, Func<T, bool> assertValue)
        {
            byte[] bytes = Serialize(value);
            Assert.Throws<InvalidDataException>(() => NewFory(required - 1).Deserialize<T>(bytes));
            Assert.True(assertValue(NewFory(required).Deserialize<T>(bytes)));
        }
    }

    [Fact]
    public void DenseLeafOwnersAreSkipped()
    {
        Assert.Equal("budget", NewFory(1).Deserialize<string>(Serialize("budget")));
        Assert.Equal(new byte[] { 1, 2, 3 }, NewFory(1).Deserialize<byte[]>(Serialize(new byte[] { 1, 2, 3 })));
        Assert.Equal(new[] { 1, 2, 3 }, NewFory(1).Deserialize<int[]>(Serialize(new[] { 1, 2, 3 })));
    }

    [Fact]
    public void CompatibleListToDenseArrayIsSkipped()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).TrackRef(false).Build();
        writer.Register<CompatibleBudgetList>(1010);
        byte[] bytes = writer.Serialize(new CompatibleBudgetList { Values = [1, 2, 3] });

        ForyRuntime reader = ForyRuntime.Builder()
            .Compatible(true)
            .TrackRef(false)
            .MaxGraphMemoryBytes(GeneratedGraphHolderBytes)
            .Build();
        reader.Register<CompatibleBudgetArray>(1010);

        Assert.Equal(new[] { 1, 2, 3 }, reader.Deserialize<CompatibleBudgetArray>(bytes).Values);
    }

    [Fact]
    public void CompatibleInlineValueFieldIsChargedByHolder()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).TrackRef(false).Build();
        writer.Register<BudgetValue>(1005).Register<BudgetValueCompatWriter>(1011);
        byte[] bytes = writer.Serialize(new BudgetValueCompatWriter { Value = new BudgetValue { Id = 9 }, Extra = 1 });

        ForyRuntime reader = ForyRuntime.Builder()
            .Compatible(true)
            .TrackRef(false)
            .MaxGraphMemoryBytes(BudgetValueHolderBytes)
            .Build();
        reader.Register<BudgetValue>(1005).Register<BudgetValueCompatReader>(1011);

        ForyRuntime tooSmall = ForyRuntime.Builder()
            .Compatible(true)
            .TrackRef(false)
            .MaxGraphMemoryBytes(BudgetValueHolderBytes - 1)
            .Build();
        tooSmall.Register<BudgetValue>(1005).Register<BudgetValueCompatReader>(1011);
        Assert.Throws<InvalidDataException>(() => tooSmall.Deserialize<BudgetValueCompatReader>(bytes));

        BudgetValueCompatReader result = reader.Deserialize<BudgetValueCompatReader>(bytes);
        Assert.Equal(9, result.Value.Id);
    }

    [Fact]
    public void ByteChecksRejectLargeLength()
    {
        byte[] bytes = Serialize(new List<string>());
        bytes[^1] = 64;
        Array.Resize(ref bytes, bytes.Length + 1);

        Assert.Throws<OutOfBoundsException>(() => NewFory().Deserialize<List<string>>(bytes));
    }
}
