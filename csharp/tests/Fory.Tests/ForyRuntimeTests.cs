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
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Collections.Immutable;
using System.Numerics;
using System.Threading.Tasks;
using Apache.Fory;
using ForyRuntime = Apache.Fory.Fory;
using S = Apache.Fory.Schema.Types;

namespace Apache.Fory.Tests;

[ForyEnum]
public enum TestColor
{
    Green,
    Red,
    Blue,
    White,
}

[ForyStruct]
public sealed class Address
{
    public string Street { get; set; } = string.Empty;
    public int Zip { get; set; }
}

[ForyStruct]
public sealed class Person
{
    public long Id { get; set; }
    public string Name { get; set; } = string.Empty;
    public string? Nickname { get; set; }
    public List<int> Scores { get; set; } = [];
    public HashSet<string> Tags { get; set; } = [];
    public List<Address> Addresses { get; set; } = [];
    public Dictionary<sbyte, int?> Metadata { get; set; } = [];
}

[ForyStruct]
public sealed class Node
{
    public int Value { get; set; }
    public Node? Next { get; set; }
}

[ForyStruct]
public sealed class FieldOrder
{
    public string Z { get; set; } = string.Empty;
    public long A { get; set; }
    public short B { get; set; }
    public int C { get; set; }
}

[ForyStruct]
public sealed class NonPrimitiveFieldOrder
{
    [ForyField(20)]
    public string StringValue { get; set; } = string.Empty;

    [ForyField(10)]
    public Dictionary<string, int> MapValue { get; set; } = [];

    public byte[] BinaryValue { get; set; } = [];
    public Address CustomValue { get; set; } = new();
    public int IntValue { get; set; }
}

public sealed class LateTypeMetaExt
{
    public int Value { get; set; }
}

public sealed class LateTypeMetaExtSerializer : Serializer<LateTypeMetaExt>
{
    public override LateTypeMetaExt DefaultValue => null!;

    public override void WriteData(WriteContext context, in LateTypeMetaExt value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt32(value?.Value ?? 0);
    }

    public override LateTypeMetaExt ReadData(ReadContext context)
    {
        return new LateTypeMetaExt { Value = context.Reader.ReadVarInt32() };
    }
}

[ForyStruct]
public sealed class LateTypeMetaHolder
{
    public LateTypeMetaExt Value { get; set; } = new();
}

[ForyStruct]
public sealed class SchemaNumbers
{
    [ForyField(Type = typeof(S.Fixed<S.UInt32>))]
    public uint U32Fixed { get; set; }

    [ForyField(Type = typeof(S.Tagged<S.UInt64>))]
    public ulong U64Tagged { get; set; }
}

[ForyStruct]
public sealed class NestedSchemaByName
{
    [ForyField(Type = typeof(S.Map<S.Fixed<S.UInt32>, S.List<S.Tagged<S.UInt64>>>))]
    public Dictionary<uint, List<ulong?>?> Values { get; set; } = [];
}

[ForyStruct]
public sealed class NestedSchemaById
{
    [ForyField(3, Type = typeof(S.Map<S.Fixed<S.UInt32>, S.List<S.Tagged<S.UInt64>>>))]
    public Dictionary<uint, List<ulong?>?> Values { get; set; } = [];
}

[ForyStruct]
public sealed class NestedSchemaSkipWriter
{
    [ForyField(Type = typeof(S.Map<S.Fixed<S.UInt32>, S.List<S.Tagged<S.UInt64>>>))]
    public Dictionary<uint, List<ulong?>?> Values { get; set; } = [];

    public int Tail { get; set; }
}

[ForyStruct]
public sealed class DefaultListSchema
{
    public List<int> Values { get; set; } = [];
}

[ForyStruct]
public sealed class ExplicitArraySchema
{
    [ForyField(Type = typeof(S.Array<S.Int32>))]
    public int[] Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleListSchema
{
    [ForyField(Type = typeof(S.List<S.Int32>))]
    public List<int> Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleNullableListSchema
{
    [ForyField(Type = typeof(S.List<S.Int32>))]
    public List<int?> Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleArraySchema
{
    [ForyField(Type = typeof(S.Array<S.Int32>))]
    public int[] Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleUInt32ListSchema
{
    [ForyField(Type = typeof(S.List<S.UInt32>))]
    public List<uint> Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleUInt32ArraySchema
{
    [ForyField(Type = typeof(S.Array<S.UInt32>))]
    public uint[] Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleUInt32ArrayListCarrierSchema
{
    [ForyField(Type = typeof(S.Array<S.UInt32>))]
    public List<uint> Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleBinarySchema
{
    [ForyField(1, Type = typeof(S.Binary))]
    public byte[] Value { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleUInt8ArrayBytesSchema
{
    [ForyField(1, Type = typeof(S.Array<S.UInt8>))]
    public byte[] Value { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleUInt8ArrayListSchema
{
    [ForyField(1, Type = typeof(S.Array<S.UInt8>))]
    public List<byte> Value { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleNestedListSchema
{
    [ForyField(Type = typeof(S.Map<S.String, S.List<S.Int32>>))]
    public Dictionary<string, List<int>> Values { get; set; } = [];
}

[ForyStruct]
public sealed class CompatibleNestedArraySchema
{
    [ForyField(Type = typeof(S.Map<S.String, S.Array<S.Int32>>))]
    public Dictionary<string, int[]> Values { get; set; } = [];
}

[ForyStruct]
public sealed class SemanticScalarSchema
{
    [ForyField(Type = typeof(S.Int32))]
    public int DefaultI32 { get; set; }

    [ForyField(Type = typeof(S.Fixed<S.Int32>))]
    public int FixedI32 { get; set; }

    [ForyField(Type = typeof(S.Tagged<S.Int64>))]
    public long TaggedI64 { get; set; }
}

[ForyStruct]
public sealed class ScalarBoolField
{
    [ForyField(1, Type = typeof(S.Bool))]
    public bool Value { get; set; }
}

[ForyStruct]
public sealed class ScalarNullableBoolField
{
    [ForyField(1, Type = typeof(S.Bool))]
    public bool? Value { get; set; }
}

[ForyStruct]
public sealed class ScalarStringField
{
    [ForyField(1, Type = typeof(S.String))]
    public string? Value { get; set; }
}

[ForyStruct]
public sealed class ScalarInt16Field
{
    [ForyField(1, Type = typeof(S.Int16))]
    public short Value { get; set; }
}

[ForyStruct]
public sealed class ScalarInt32Field
{
    [ForyField(1, Type = typeof(S.Int32))]
    public int Value { get; set; }
}

[ForyStruct]
public sealed class ScalarVarInt32Field
{
    [ForyField(1)]
    public int Value { get; set; }
}

[ForyStruct]
public sealed class ScalarNullableInt32Field
{
    [ForyField(1, Type = typeof(S.Int32))]
    public int? Value { get; set; }
}

[ForyStruct]
public sealed class ScalarInt64Field
{
    [ForyField(1, Type = typeof(S.Int64))]
    public long Value { get; set; }
}

[ForyStruct]
public sealed class ScalarNullableInt64Field
{
    [ForyField(1, Type = typeof(S.Int64))]
    public long? Value { get; set; }
}

[ForyStruct]
public sealed class ScalarUInt32Field
{
    [ForyField(1, Type = typeof(S.UInt32))]
    public uint Value { get; set; }
}

[ForyStruct]
public sealed class ScalarUInt64Field
{
    [ForyField(1, Type = typeof(S.UInt64))]
    public ulong Value { get; set; }
}

[ForyStruct]
public sealed class ScalarFixedUInt32Field
{
    [ForyField(1, Type = typeof(S.Fixed<S.UInt32>))]
    public uint Value { get; set; }
}

[ForyStruct]
public sealed class ScalarNullableFixedUInt32Field
{
    [ForyField(1, Type = typeof(S.Fixed<S.UInt32>))]
    public uint? Value { get; set; }
}

[ForyStruct]
public sealed class ScalarFloat32Field
{
    [ForyField(1, Type = typeof(S.Float32))]
    public float Value { get; set; }
}

[ForyStruct]
public sealed class ScalarFloat64Field
{
    [ForyField(1, Type = typeof(S.Float64))]
    public double Value { get; set; }
}

[ForyStruct]
public sealed class ScalarDecimalField
{
    [ForyField(1, Type = typeof(S.Decimal))]
    public ForyDecimal Value { get; set; }
}

[ForyStruct]
public sealed class ScalarStringListField
{
    [ForyField(1, Type = typeof(S.List<S.String>))]
    public List<string> Values { get; set; } = [];
}

[ForyStruct]
public sealed class ScalarInt32ListField
{
    [ForyField(1, Type = typeof(S.List<S.Int32>))]
    public List<int> Values { get; set; } = [];
}

[ForyStruct]
public sealed class ScalarTwoStringFields
{
    [ForyField(1, Type = typeof(S.String))]
    public string? Flag { get; set; }

    [ForyField(2, Type = typeof(S.String))]
    public string? Count { get; set; }
}

[ForyStruct]
public sealed class ScalarBoolIntFields
{
    [ForyField(1, Type = typeof(S.Bool))]
    public bool Flag { get; set; }

    [ForyField(2, Type = typeof(S.Int32))]
    public int Count { get; set; }
}

[ForyStruct]
public sealed class NestedSchemaSkipReader
{
    public int Tail { get; set; }
}

[ForyStruct]
public sealed class OneStringField
{
    public string? F1 { get; set; }
}

[ForyStruct]
public sealed class TwoStringField
{
    public string F1 { get; set; } = string.Empty;
    public string F2 { get; set; } = string.Empty;
}

[ForyStruct]
public sealed class EvolvingOverrideValue
{
    public string F1 { get; set; } = string.Empty;
}

[ForyStruct(Evolving = false)]
public sealed class FixedOverrideValue
{
    public string F1 { get; set; } = string.Empty;
}

[ForyStruct]
public sealed class OneStringFieldListHolder
{
    public List<OneStringField?> Items { get; set; } = [];
}

[ForyStruct]
public sealed class TwoStringFieldListHolder
{
    public List<TwoStringField?> Items { get; set; } = [];
}

[ForyStruct]
public sealed class OneStringFieldMapHolder
{
    public Dictionary<string, OneStringField?> Items { get; set; } = [];
}

[ForyStruct]
public sealed class TwoStringFieldMapHolder
{
    public Dictionary<string, TwoStringField?> Items { get; set; } = [];
}

[ForyStruct]
public sealed class UnsignedFields
{
    public byte U8 { get; set; }
    public ushort U16 { get; set; }
    public uint U32 { get; set; }
    public ulong U64 { get; set; }
    public byte? U8Nullable { get; set; }
    public ushort? U16Nullable { get; set; }
    public uint? U32Nullable { get; set; }
    public ulong? U64Nullable { get; set; }
}

[ForyStruct]
public sealed class StructWithEnum
{
    public string Name { get; set; } = string.Empty;
    public TestColor Color { get; set; }
    public int Value { get; set; }
}

[ForyStruct]
public sealed class StructWithNullableMap
{
    public NullableKeyDictionary<string, string?> Data { get; set; } = new();
}

[ForyStruct]
public sealed class StructWithUnion2
{
    public Union2<string, long> Union { get; set; } = Union2<string, long>.OfT1(string.Empty);
}

[ForyUnion]
public abstract partial record SourceGeneratedShape
{
    private SourceGeneratedShape()
    {
    }

    [ForyUnknownCase]
    public sealed partial record Unknown(UnknownCase Value) : SourceGeneratedShape;

    [ForyCase(0)]
    public sealed partial record Text(string Value) : SourceGeneratedShape;

    [ForyCase(1, Type = typeof(S.Fixed<S.Int32>))]
    public sealed partial record Number(int Value) : SourceGeneratedShape;
}

[ForyStruct]
public sealed class SourceGeneratedUnionHolder
{
    public SourceGeneratedShape Shape { get; set; } =
        new SourceGeneratedShape.Text(string.Empty);
}

[ForyStruct]
public sealed class DynamicAnyHolder
{
    public object? AnyValue { get; set; }
    public HashSet<object> AnySet { get; set; } = [];
    public Dictionary<object, object?> AnyMap { get; set; } = [];
}

[ForyStruct]
public sealed class DictionaryContainerHolder
{
    public Dictionary<string, int> DictionaryField { get; set; } = [];
    public SortedDictionary<string, int> SortedField { get; set; } = new();
    public SortedList<string, int> SortedListField { get; set; } = new();
    public ConcurrentDictionary<string, int> ConcurrentField { get; set; } = new();
}

[ForyStruct]
public sealed class CollectionContainerHolder
{
    public LinkedList<int> LinkedListField { get; set; } = new();
    public SortedSet<int> SortedSetField { get; set; } = new();
    public ImmutableHashSet<int> ImmutableHashSetField { get; set; } = ImmutableHashSet<int>.Empty;
    public Queue<int> QueueField { get; set; } = new();
    public Stack<int> StackField { get; set; } = new();
}

[ForyStruct]
public sealed class NestedTypedContainers
{
    public List<List<string>> NestedLists { get; set; } = [];
    public Dictionary<string, List<Address?>> NestedMap { get; set; } = [];
}

public sealed class ForyRuntimeTests
{
    private const ulong StringEncodingLatin1 = 0;
    private const ulong StringEncodingUtf16 = 1;
    private const ulong StringEncodingUtf8 = 2;

    [Fact]
    public void BuilderDefaultsToCompatibleUnlessExplicitlySet()
    {
        ForyRuntime defaultRuntime = ForyRuntime.Builder().Build();
        ForyRuntime compatibleWithVersionCheck = ForyRuntime.Builder().Compatible(true).CheckStructVersion(true).Build();
        ForyRuntime explicitSchemaConsistent = ForyRuntime.Builder().Compatible(false).Build();

        Assert.True(defaultRuntime.Config.Compatible);
        Assert.False(defaultRuntime.Config.CheckStructVersion);
        Assert.False(compatibleWithVersionCheck.Config.CheckStructVersion);
        Assert.False(explicitSchemaConsistent.Config.Compatible);
    }

    [Fact]
    public void PrimitiveRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        Assert.True(fory.Deserialize<bool>(fory.Serialize(true)));
        Assert.Equal(-123_456, fory.Deserialize<int>(fory.Serialize(-123_456)));
        Assert.Equal(9_223_372_036_854_775_000L, fory.Deserialize<long>(fory.Serialize(9_223_372_036_854_775_000L)));
        Assert.Equal(123_456u, fory.Deserialize<uint>(fory.Serialize(123_456u)));
        Assert.Equal(9_223_372_036_854_775_000UL, fory.Deserialize<ulong>(fory.Serialize(9_223_372_036_854_775_000UL)));
        Assert.Equal(3.25f, fory.Deserialize<float>(fory.Serialize(3.25f)));
        Assert.Equal(3.1415926, fory.Deserialize<double>(fory.Serialize(3.1415926)));
        Assert.Equal("hello_fory", fory.Deserialize<string>(fory.Serialize("hello_fory")));

        byte[] binary = [0x01, 0x02, 0x03, 0xFF];
        Assert.Equal(binary, fory.Deserialize<byte[]>(fory.Serialize(binary)));
    }

    [Fact]
    public void OptionalRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        string? present = "present";
        string? absent = null;
        Assert.Equal("present", fory.Deserialize<string?>(fory.Serialize(present)));
        Assert.Null(fory.Deserialize<string?>(fory.Serialize(absent)));
    }

    [Fact]
    public void ForyReusedContextsHandleSequentialCalls()
    {
        ForyRuntime fory = ForyRuntime.Builder().TrackRef(true).Compatible(true).Build();
        fory.Register<Node>(950);

        for (int i = 0; i < 32; i++)
        {
            Node source = new() { Value = i };
            source.Next = source;

            Node decoded = fory.Deserialize<Node>(fory.Serialize(source));
            Assert.Equal(i, decoded.Value);
            Assert.NotNull(decoded.Next);
            Assert.Same(decoded, decoded.Next);
        }
    }

    [Fact]
    public void ThreadSafeForySupportsParallelPrimitiveRoundTrip()
    {
        using ThreadSafeFory fory = ForyRuntime.Builder().BuildThreadSafe();
        Parallel.For(0, 512, i =>
        {
            byte[] payload = fory.Serialize(i);
            int decoded = fory.Deserialize<int>(payload);
            Assert.Equal(i, decoded);
        });
    }

    [Fact]
    public void ThreadSafeForyPropagatesRegistrationsToThreads()
    {
        using ThreadSafeFory fory = ForyRuntime.Builder().TrackRef(true).BuildThreadSafe();
        fory.Register<Node>(951);

        Parallel.For(0, 128, i =>
        {
            Node source = new() { Value = i };
            source.Next = source;
            Node decoded = fory.Deserialize<Node>(fory.Serialize(source));
            Assert.Equal(i, decoded.Value);
            Assert.NotNull(decoded.Next);
            Assert.Same(decoded, decoded.Next);
        });
    }

    [Fact]
    public void ThreadSafeForyRegistrationAppliesToInitializedThreadLocalInstance()
    {
        using ThreadSafeFory fory = ForyRuntime.Builder().TrackRef(true).BuildThreadSafe();
        _ = fory.Serialize(1);
        fory.Register<Node>(952);

        Node source = new() { Value = 7 };
        source.Next = source;
        Node decoded = fory.Deserialize<Node>(fory.Serialize(source));
        Assert.Equal(7, decoded.Value);
        Assert.NotNull(decoded.Next);
        Assert.Same(decoded, decoded.Next);
    }

    [Fact]
    public void CollectionsRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        List<string?> list = ["a", null, "b"];
        Assert.Equal(list, fory.Deserialize<List<string?>>(fory.Serialize(list)));

        int[] intArray = [1, 2, 3, 4];
        Assert.Equal(intArray, fory.Deserialize<int[]>(fory.Serialize(intArray)));

        byte[] bytes = [1, 2, 3, 250];
        Assert.Equal(bytes, fory.Deserialize<byte[]>(fory.Serialize(bytes)));

        HashSet<short> set = [1, 5, 8];
        Assert.Equal(set, fory.Deserialize<HashSet<short>>(fory.Serialize(set)));

        Dictionary<sbyte, int?> map = new() { [1] = 100, [2] = null, [3] = -7 };
        Dictionary<sbyte, int?> decoded = fory.Deserialize<Dictionary<sbyte, int?>>(fory.Serialize(map));
        Assert.Equal(map.Count, decoded.Count);
        foreach ((sbyte key, int? value) in map)
        {
            Assert.Equal(value, decoded[key]);
        }
    }

    [Fact]
    public void NumericListSetRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        Assert.Equal((List<sbyte>)[-5, 0, 12], fory.Deserialize<List<sbyte>>(fory.Serialize((List<sbyte>)[-5, 0, 12])));
        Assert.Equal((List<short>)[-1200, 0, 32000], fory.Deserialize<List<short>>(fory.Serialize((List<short>)[-1200, 0, 32000])));
        Assert.Equal((List<int>)[-200_000, 0, 500_000], fory.Deserialize<List<int>>(fory.Serialize((List<int>)[-200_000, 0, 500_000])));
        Assert.Equal((List<long>)[-9_000_000_000, 0, 9_000_000_000], fory.Deserialize<List<long>>(fory.Serialize((List<long>)[-9_000_000_000, 0, 9_000_000_000])));
        Assert.Equal((List<byte>)[0, 1, 200], fory.Deserialize<List<byte>>(fory.Serialize((List<byte>)[0, 1, 200])));
        Assert.Equal((List<ushort>)[0, 1, 65000], fory.Deserialize<List<ushort>>(fory.Serialize((List<ushort>)[0, 1, 65000])));
        Assert.Equal((List<uint>)[0, 1, 4_000_000_000], fory.Deserialize<List<uint>>(fory.Serialize((List<uint>)[0, 1, 4_000_000_000])));
        Assert.Equal((List<ulong>)[0, 1, 12_000_000_000], fory.Deserialize<List<ulong>>(fory.Serialize((List<ulong>)[0, 1, 12_000_000_000])));
        Assert.Equal((List<float>)[-2.5f, 0f, 7.25f], fory.Deserialize<List<float>>(fory.Serialize((List<float>)[-2.5f, 0f, 7.25f])));
        Assert.Equal((List<double>)[-2.5, 0d, 7.25], fory.Deserialize<List<double>>(fory.Serialize((List<double>)[-2.5, 0d, 7.25])));

        Assert.Equal((HashSet<sbyte>)[-5, 0, 12], fory.Deserialize<HashSet<sbyte>>(fory.Serialize((HashSet<sbyte>)[-5, 0, 12])));
        Assert.Equal((HashSet<short>)[-1200, 0, 32000], fory.Deserialize<HashSet<short>>(fory.Serialize((HashSet<short>)[-1200, 0, 32000])));
        Assert.Equal((HashSet<int>)[-200_000, 0, 500_000], fory.Deserialize<HashSet<int>>(fory.Serialize((HashSet<int>)[-200_000, 0, 500_000])));
        Assert.Equal((HashSet<long>)[-9_000_000_000, 0, 9_000_000_000], fory.Deserialize<HashSet<long>>(fory.Serialize((HashSet<long>)[-9_000_000_000, 0, 9_000_000_000])));
        Assert.Equal((HashSet<byte>)[0, 1, 200], fory.Deserialize<HashSet<byte>>(fory.Serialize((HashSet<byte>)[0, 1, 200])));
        Assert.Equal((HashSet<ushort>)[0, 1, 65000], fory.Deserialize<HashSet<ushort>>(fory.Serialize((HashSet<ushort>)[0, 1, 65000])));
        Assert.Equal((HashSet<uint>)[0, 1, 4_000_000_000], fory.Deserialize<HashSet<uint>>(fory.Serialize((HashSet<uint>)[0, 1, 4_000_000_000])));
        Assert.Equal((HashSet<ulong>)[0, 1, 12_000_000_000], fory.Deserialize<HashSet<ulong>>(fory.Serialize((HashSet<ulong>)[0, 1, 12_000_000_000])));
        Assert.Equal((HashSet<float>)[-2.5f, 0f, 7.25f], fory.Deserialize<HashSet<float>>(fory.Serialize((HashSet<float>)[-2.5f, 0f, 7.25f])));
        Assert.Equal((HashSet<double>)[-2.5, 0d, 7.25], fory.Deserialize<HashSet<double>>(fory.Serialize((HashSet<double>)[-2.5, 0d, 7.25])));
    }

    [Fact]
    public void PrimitiveStringDictionaryRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        static void AssertMapRoundTrip<T>(ForyRuntime runtime, Dictionary<string, T> source)
        {
            Dictionary<string, T> decoded = runtime.Deserialize<Dictionary<string, T>>(runtime.Serialize(source));
            Assert.Equal(source.Count, decoded.Count);
            foreach ((string key, T value) in source)
            {
                Assert.Equal(value, decoded[key]);
            }
        }

        AssertMapRoundTrip(fory, new Dictionary<string, float> { ["a"] = -1.25f, ["b"] = 7.5f });
        AssertMapRoundTrip(fory, new Dictionary<string, uint> { ["a"] = 1, ["b"] = 4_000_000_000 });
        AssertMapRoundTrip(fory, new Dictionary<string, ulong> { ["a"] = 1, ["b"] = 12_000_000_000 });
        AssertMapRoundTrip(fory, new Dictionary<string, sbyte> { ["a"] = -7, ["b"] = 120 });
        AssertMapRoundTrip(fory, new Dictionary<string, short> { ["a"] = -32000, ["b"] = 12345 });
        AssertMapRoundTrip(fory, new Dictionary<string, ushort> { ["a"] = 1, ["b"] = 65000 });
    }

    [Fact]
    public void PrimitiveUnsignedDictionaryRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        static void AssertMapRoundTrip<TKey, TValue>(ForyRuntime runtime, Dictionary<TKey, TValue> source)
            where TKey : notnull
        {
            Dictionary<TKey, TValue> decoded = runtime.Deserialize<Dictionary<TKey, TValue>>(runtime.Serialize(source));
            Assert.Equal(source.Count, decoded.Count);
            foreach ((TKey key, TValue value) in source)
            {
                Assert.Equal(value, decoded[key]);
            }
        }

        AssertMapRoundTrip(fory, new Dictionary<uint, uint> { [1] = 7, [2] = 4_000_000_000 });
        AssertMapRoundTrip(fory, new Dictionary<ulong, ulong> { [1] = 7, [2] = 12_000_000_000 });
    }

    [Fact]
    public void SortedAndConcurrentDictionaryRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        SortedDictionary<string, int> sorted = new()
        {
            ["b"] = 2,
            ["a"] = 1,
        };
        SortedDictionary<string, int> sortedDecoded =
            fory.Deserialize<SortedDictionary<string, int>>(fory.Serialize(sorted));
        Assert.Equal(sorted.Count, sortedDecoded.Count);
        foreach ((string key, int value) in sorted)
        {
            Assert.Equal(value, sortedDecoded[key]);
        }

        SortedList<string, int> sortedList = new()
        {
            ["b"] = 2,
            ["a"] = 1,
        };
        SortedList<string, int> sortedListDecoded =
            fory.Deserialize<SortedList<string, int>>(fory.Serialize(sortedList));
        Assert.Equal(sortedList.Count, sortedListDecoded.Count);
        foreach ((string key, int value) in sortedList)
        {
            Assert.Equal(value, sortedListDecoded[key]);
        }

        ConcurrentDictionary<string, int> concurrent = new();
        concurrent["x"] = 7;
        concurrent["y"] = 9;
        ConcurrentDictionary<string, int> concurrentDecoded =
            fory.Deserialize<ConcurrentDictionary<string, int>>(fory.Serialize(concurrent));
        Assert.Equal(concurrent.Count, concurrentDecoded.Count);
        foreach ((string key, int value) in concurrent)
        {
            Assert.Equal(value, concurrentDecoded[key]);
        }
    }

    [Fact]
    public void AdditionalCollectionsRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        LinkedList<int> linkedList = new(new[] { 1, 2, 3 });
        LinkedList<int> linkedListDecoded = fory.Deserialize<LinkedList<int>>(fory.Serialize(linkedList));
        Assert.Equal(linkedList.ToArray(), linkedListDecoded.ToArray());

        SortedSet<int> sortedSet = [3, 1, 2];
        SortedSet<int> sortedSetDecoded = fory.Deserialize<SortedSet<int>>(fory.Serialize(sortedSet));
        Assert.True(sortedSetDecoded.SetEquals(sortedSet));

        ImmutableHashSet<int> immutableHashSet = ImmutableHashSet.Create(4, 5, 6);
        ImmutableHashSet<int> immutableHashSetDecoded =
            fory.Deserialize<ImmutableHashSet<int>>(fory.Serialize(immutableHashSet));
        Assert.True(immutableHashSetDecoded.SetEquals(immutableHashSet));

        Queue<int> queue = new();
        queue.Enqueue(10);
        queue.Enqueue(20);
        queue.Enqueue(30);
        Queue<int> queueDecoded = fory.Deserialize<Queue<int>>(fory.Serialize(queue));
        Assert.Equal(queue.ToArray(), queueDecoded.ToArray());

        Stack<int> stack = new();
        stack.Push(100);
        stack.Push(200);
        stack.Push(300);
        Stack<int> stackDecoded = fory.Deserialize<Stack<int>>(fory.Serialize(stack));
        Assert.Equal(stack.ToArray(), stackDecoded.ToArray());
    }

    [Fact]
    public void GeneratedSerializerSupportsSortedAndConcurrentDictionaryFields()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<DictionaryContainerHolder>(450);

        DictionaryContainerHolder source = new()
        {
            DictionaryField = new Dictionary<string, int>
            {
                ["d1"] = 1,
                ["d2"] = 2,
            },
            SortedField = new SortedDictionary<string, int>
            {
                ["s1"] = 10,
                ["s2"] = 20,
            },
            SortedListField = new SortedList<string, int>
            {
                ["sl1"] = 1000,
                ["sl2"] = 2000,
            },
            ConcurrentField = new ConcurrentDictionary<string, int>(
                new Dictionary<string, int>
                {
                    ["c1"] = 100,
                    ["c2"] = 200,
                }),
        };

        DictionaryContainerHolder decoded = fory.Deserialize<DictionaryContainerHolder>(fory.Serialize(source));
        Assert.Equal(source.DictionaryField.Count, decoded.DictionaryField.Count);
        Assert.Equal(source.SortedField.Count, decoded.SortedField.Count);
        Assert.Equal(source.SortedListField.Count, decoded.SortedListField.Count);
        Assert.Equal(source.ConcurrentField.Count, decoded.ConcurrentField.Count);
        foreach ((string key, int value) in source.DictionaryField)
        {
            Assert.Equal(value, decoded.DictionaryField[key]);
        }

        foreach ((string key, int value) in source.SortedField)
        {
            Assert.Equal(value, decoded.SortedField[key]);
        }

        foreach ((string key, int value) in source.SortedListField)
        {
            Assert.Equal(value, decoded.SortedListField[key]);
        }

        foreach ((string key, int value) in source.ConcurrentField)
        {
            Assert.Equal(value, decoded.ConcurrentField[key]);
        }
    }

    [Fact]
    public void GeneratedSerializerSupportsAdditionalCollectionFields()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<CollectionContainerHolder>(451);

        CollectionContainerHolder source = new()
        {
            LinkedListField = new LinkedList<int>(new[] { 1, 2, 3 }),
            SortedSetField = new SortedSet<int> { 11, 7, 9 },
            ImmutableHashSetField = ImmutableHashSet.Create(21, 22, 23),
        };
        source.QueueField.Enqueue(31);
        source.QueueField.Enqueue(32);
        source.QueueField.Enqueue(33);
        source.StackField.Push(41);
        source.StackField.Push(42);
        source.StackField.Push(43);

        CollectionContainerHolder decoded = fory.Deserialize<CollectionContainerHolder>(fory.Serialize(source));
        Assert.Equal(source.LinkedListField.ToArray(), decoded.LinkedListField.ToArray());
        Assert.True(decoded.SortedSetField.SetEquals(source.SortedSetField));
        Assert.True(decoded.ImmutableHashSetField.SetEquals(source.ImmutableHashSetField));
        Assert.Equal(source.QueueField.ToArray(), decoded.QueueField.ToArray());
        Assert.Equal(source.StackField.ToArray(), decoded.StackField.ToArray());
    }

    [Fact]
    public void GeneratedSerializerSupportsNestedTypedContainers()
    {
        ForyRuntime fory = ForyRuntime.Builder().TrackRef(true).Build();
        fory.Register<Address>(452);
        fory.Register<NestedTypedContainers>(453);

        Address shared = new() { Street = "Main", Zip = 94107 };
        NestedTypedContainers source = new()
        {
            NestedLists =
            [
                ["a", "b"],
                ["c"],
            ],
            NestedMap = new Dictionary<string, List<Address?>>
            {
                ["first"] = [shared, null],
                ["second"] = [shared],
            },
        };

        NestedTypedContainers decoded = fory.Deserialize<NestedTypedContainers>(fory.Serialize(source));
        Assert.Equal(source.NestedLists.Count, decoded.NestedLists.Count);
        Assert.Equal(source.NestedLists[0], decoded.NestedLists[0]);
        Assert.Equal(source.NestedLists[1], decoded.NestedLists[1]);

        Assert.Equal(2, decoded.NestedMap["first"].Count);
        Assert.Single(decoded.NestedMap["second"]);
        Assert.NotNull(decoded.NestedMap["first"][0]);
        Assert.Same(decoded.NestedMap["first"][0], decoded.NestedMap["second"][0]);
        Assert.Null(decoded.NestedMap["first"][1]);
    }

    [Fact]
    public void StreamDeserializeConsumesSingleFrame()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        byte[] p1 = fory.Serialize(11);
        byte[] p2 = fory.Serialize(22);
        byte[] joined = new byte[p1.Length + p2.Length];
        Buffer.BlockCopy(p1, 0, joined, 0, p1.Length);
        Buffer.BlockCopy(p2, 0, joined, p1.Length, p2.Length);

        ReadOnlySequence<byte> sequence = new(joined);
        int first = fory.Deserialize<int>(ref sequence);
        int second = fory.Deserialize<int>(ref sequence);

        Assert.Equal(11, first);
        Assert.Equal(22, second);
        Assert.Equal(0, sequence.Length);
    }

    [Fact]
    public void StreamDeserializeGenericObjectConsumesSingleFrame()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        byte[] p1 = fory.Serialize<object?>("first");
        byte[] p2 = fory.Serialize<object?>(99);
        byte[] joined = new byte[p1.Length + p2.Length];
        Buffer.BlockCopy(p1, 0, joined, 0, p1.Length);
        Buffer.BlockCopy(p2, 0, joined, p1.Length, p2.Length);

        ReadOnlySequence<byte> sequence = new(joined);
        object? first = fory.Deserialize<object?>(ref sequence);
        object? second = fory.Deserialize<object?>(ref sequence);

        Assert.Equal("first", first);
        Assert.Equal(99, second);
        Assert.Equal(0, sequence.Length);
    }

    [Fact]
    public void MacroStructRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<Address>(100);
        fory.Register<Person>(101);

        Person person = new()
        {
            Id = 42,
            Name = "Alice",
            Nickname = null,
            Scores = [10, 20, 30],
            Tags = ["swift", "xlang"],
            Addresses = [new Address { Street = "Main", Zip = 94107 }],
            Metadata = new Dictionary<sbyte, int?> { [1] = 100, [2] = null },
        };

        Person decoded = fory.Deserialize<Person>(fory.Serialize(person));
        Assert.Equal(person.Id, decoded.Id);
        Assert.Equal(person.Name, decoded.Name);
        Assert.Equal(person.Nickname, decoded.Nickname);
        Assert.Equal(person.Scores, decoded.Scores);
        Assert.Equal(person.Tags, decoded.Tags);
        Assert.Single(decoded.Addresses);
        Assert.Equal(person.Addresses[0].Street, decoded.Addresses[0].Street);
        Assert.Equal(person.Addresses[0].Zip, decoded.Addresses[0].Zip);
        Assert.Equal(person.Metadata.Count, decoded.Metadata.Count);
        foreach ((sbyte key, int? value) in person.Metadata)
        {
            Assert.Equal(value, decoded.Metadata[key]);
        }
    }

    [Fact]
    public void MacroClassRefTracking()
    {
        ForyRuntime fory = ForyRuntime.Builder().TrackRef(true).Build();
        fory.Register<Node>(200);

        Node node = new() { Value = 7 };
        node.Next = node;

        Node decoded = fory.Deserialize<Node>(fory.Serialize(node));
        Assert.Equal(7, decoded.Value);
        Assert.Same(decoded, decoded.Next);
    }

    [Fact]
    public void NullableKeyDictionarySupportsNullKeyRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(true).Build();

        NullableKeyDictionary<string, string?> map = new();
        map.Add("k1", "v1");
        map.Add((string)null!, "v2");
        map.Add("k3", null);
        map.Add("k4", "v4");

        NullableKeyDictionary<string, string?> decoded = fory.Deserialize<NullableKeyDictionary<string, string?>>(fory.Serialize(map));
        Assert.True(decoded.HasNullKey);
        Assert.Equal("v2", decoded.NullKeyValue);
        Assert.True(decoded.TryGetValue("k1", out string? v1));
        Assert.Equal("v1", v1);
        Assert.True(decoded.TryGetValue("k3", out string? v3));
        Assert.Null(v3);
    }

    [Fact]
    public void NullableKeyDictionarySupportsDropInDictionaryBehavior()
    {
        IDictionary<string, string?> map = new NullableKeyDictionary<string, string?>();
        map.Add("k1", "v1");
        map.Add(null!, "v2");

        Assert.Throws<ArgumentException>(() => map.Add("k1", "dup"));
        Assert.Throws<ArgumentException>(() => map.Add(null!, "dup"));

        map["k1"] = "v1-updated";
        map[null!] = "v2-updated";

        Assert.True(map.ContainsKey("k1"));
        Assert.True(map.ContainsKey(null!));
        Assert.Equal("v1-updated", map["k1"]);
        Assert.Equal("v2-updated", map[null!]);
        Assert.True(map.TryGetValue(null!, out string? nullValue));
        Assert.Equal("v2-updated", nullValue);
        Assert.True(map.Remove(null!));
        Assert.False(map.ContainsKey(null!));
    }

    [Fact]
    public void DictionarySerializerSkipsNullKeyEntries()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(true).Build();

        NullableKeyDictionary<string, string?> source = new();
        source.Add("k1", "v1");
        source.Add((string)null!, "v-null");
        source.Add("k2", "v2");

        Dictionary<string, string?> decoded = fory.Deserialize<Dictionary<string, string?>>(fory.Serialize(source));
        Assert.Equal(2, decoded.Count);
        Assert.Equal("v1", decoded["k1"]);
        Assert.Equal("v2", decoded["k2"]);
    }

    [Fact]
    public void StructWithNullableMapRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(true).Build();
        fory.Register<StructWithNullableMap>(202);

        StructWithNullableMap value = new();
        value.Data.Add("key1", "value1");
        value.Data.Add((string)null!, "value2");
        value.Data.Add("key3", null);

        StructWithNullableMap decoded = fory.Deserialize<StructWithNullableMap>(fory.Serialize(value));
        Assert.True(decoded.Data.HasNullKey);
        Assert.Equal("value2", decoded.Data.NullKeyValue);
        Assert.True(decoded.Data.TryGetValue("key1", out string? key1));
        Assert.Equal("value1", key1);
        Assert.True(decoded.Data.TryGetValue("key3", out string? key3));
        Assert.Null(key3);
    }

    [Fact]
    public void StructEvolvingOverrideUsesSmallerCompatiblePayload()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(true).Build();
        fory.Register<EvolvingOverrideValue>(1001);
        fory.Register<FixedOverrideValue>(1002);

        EvolvingOverrideValue evolving = new() { F1 = "payload" };
        FixedOverrideValue fixedValue = new() { F1 = "payload" };

        byte[] evolvingPayload = fory.Serialize(evolving);
        byte[] fixedPayload = fory.Serialize(fixedValue);

        Assert.True(fixedPayload.Length < evolvingPayload.Length);
        Assert.Equal("payload", fory.Deserialize<EvolvingOverrideValue>(evolvingPayload).F1);
        Assert.Equal("payload", fory.Deserialize<FixedOverrideValue>(fixedPayload).F1);
    }

    [Fact]
    public void MacroFieldOrderFollowsForyRules()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(false).CheckStructVersion(true).Build();
        fory.Register<FieldOrder>(300);

        FieldOrder value = new() { Z = "tail", A = 123_456_789, B = 17, C = 99 };
        byte[] data = fory.Serialize(value);

        ByteReader reader = new(data);
        fory.ReadHead(reader);
        _ = reader.ReadInt8();
        _ = reader.ReadVarUInt32();
        _ = reader.ReadVarUInt32();
        _ = reader.ReadInt32();

        short first = reader.ReadInt16();
        long second = reader.ReadVarInt64();
        int third = reader.ReadVarInt32();
        ReadContext tailContext = new(reader, new TypeResolver(), fory.Config);
        string fourth = tailContext.TypeResolver.GetSerializer<string>().ReadData(tailContext);

        Assert.Equal(value.B, first);
        Assert.Equal(value.A, second);
        Assert.Equal(value.C, third);
        Assert.Equal(value.Z, fourth);
    }

    [Fact]
    public void TypeMetaFieldsOrderNonPrimitivesByFieldIdentifier()
    {
        TypeResolver resolver = new();
        List<string> fieldNames = resolver.GetTypeInfo<NonPrimitiveFieldOrder>()
            .TypeMetaFields(false)
            .Select(field => field.FieldName)
            .ToList();

        Assert.Equal(
            ["int_value", "map_value", "string_value", "binary_value", "custom_value"],
            fieldNames);
    }

    [Fact]
    public void TypeMetaUsesLateRegisteredFieldType()
    {
        Assert.Equal(
            LateHolderTypeMetaBytes(registerExtFirst: true),
            LateHolderTypeMetaBytes(registerExtFirst: false));
    }

    [Fact]
    public void ForyFieldSchemaTypeOverridesForUnsignedTypes()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<SchemaNumbers>(301);

        SchemaNumbers value = new()
        {
            U32Fixed = 0x11223344u,
            U64Tagged = (ulong)int.MaxValue + 99UL,
        };

        SchemaNumbers decoded = fory.Deserialize<SchemaNumbers>(fory.Serialize(value));
        Assert.Equal(value.U32Fixed, decoded.U32Fixed);
        Assert.Equal(value.U64Tagged, decoded.U64Tagged);
    }

    [Fact]
    public void ForyFieldTypeWithoutIdUsesNameBasedNestedSchema()
    {
        TypeResolver resolver = new();
        resolver.GetTypeInfo<NestedSchemaByName>();
        TypeMetaFieldInfo field = Assert.Single(resolver.GetTypeInfo<NestedSchemaByName>().TypeMetaFields(false));

        Assert.Null(field.FieldId);
        Assert.Equal("values", field.FieldName);
        Assert.Equal((uint)TypeId.Map, field.FieldType.TypeId);
        Assert.Equal((uint)TypeId.UInt32, field.FieldType.Generics[0].TypeId);
        Assert.Equal((uint)TypeId.List, field.FieldType.Generics[1].TypeId);
        Assert.True(field.FieldType.Generics[1].Nullable);
        Assert.Equal((uint)TypeId.TaggedUInt64, field.FieldType.Generics[1].Generics[0].TypeId);
        Assert.True(field.FieldType.Generics[1].Generics[0].Nullable);
    }

    [Fact]
    public void ForyFieldTypeWithIdUsesAssignedIdNestedSchema()
    {
        TypeResolver resolver = new();
        resolver.GetTypeInfo<NestedSchemaById>();
        TypeMetaFieldInfo field = Assert.Single(resolver.GetTypeInfo<NestedSchemaById>().TypeMetaFields(false));

        Assert.Equal((short)3, field.FieldId);
        Assert.Equal((uint)TypeId.Map, field.FieldType.TypeId);
        Assert.Equal((uint)TypeId.UInt32, field.FieldType.Generics[0].TypeId);
        Assert.Equal((uint)TypeId.TaggedUInt64, field.FieldType.Generics[1].Generics[0].TypeId);
    }

    [Fact]
    public void UnannotatedListCarrierUsesListSchema()
    {
        TypeResolver resolver = new();
        resolver.GetTypeInfo<DefaultListSchema>();
        TypeMetaFieldInfo field = Assert.Single(resolver.GetTypeInfo<DefaultListSchema>().TypeMetaFields(false));

        Assert.Equal((uint)TypeId.List, field.FieldType.TypeId);
        TypeMetaFieldType element = Assert.Single(field.FieldType.Generics);
        Assert.Equal((uint)TypeId.VarInt32, element.TypeId);
    }

    [Fact]
    public void GenericSchemaMarkersDescribeScalarEncodingAndArraySchema()
    {
        TypeResolver resolver = new();
        resolver.GetTypeInfo<SemanticScalarSchema>();
        Dictionary<string, TypeMetaFieldType> scalarFields = resolver.GetTypeInfo<SemanticScalarSchema>()
            .TypeMetaFields(false)
            .ToDictionary(f => f.FieldName, f => f.FieldType);

        Assert.Equal((uint)TypeId.VarInt32, scalarFields["default_i32"].TypeId);
        Assert.Equal((uint)TypeId.Int32, scalarFields["fixed_i32"].TypeId);
        Assert.Equal((uint)TypeId.TaggedInt64, scalarFields["tagged_i64"].TypeId);

        resolver.GetTypeInfo<ExplicitArraySchema>();
        TypeMetaFieldInfo arrayField = Assert.Single(resolver.GetTypeInfo<ExplicitArraySchema>().TypeMetaFields(false));
        Assert.Equal((uint)TypeId.Int32Array, arrayField.FieldType.TypeId);
        Assert.Empty(arrayField.FieldType.Generics);
    }

    [Fact]
    public void ExplicitArrayMarkerRoundTripsDenseArrayPayload()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<ExplicitArraySchema>(305);

        ExplicitArraySchema value = new() { Values = [1, -2, int.MaxValue] };
        ExplicitArraySchema decoded = fory.Deserialize<ExplicitArraySchema>(fory.Serialize(value));

        Assert.Equal(value.Values, decoded.Values);
    }

    [Fact]
    public void CompatibleReadAllowsImmediateListFieldIntoArrayCarrier()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).Build();
        writer.Register<CompatibleListSchema>(306);
        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).Build();
        reader.Register<CompatibleArraySchema>(306);

        CompatibleArraySchema decoded = reader.Deserialize<CompatibleArraySchema>(
            writer.Serialize(new CompatibleListSchema { Values = [1, -2, int.MaxValue] }));

        Assert.Equal([1, -2, int.MaxValue], decoded.Values);
    }

    [Fact]
    public void CompatibleReadAllowsImmediateArrayFieldIntoListCarrier()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).Build();
        writer.Register<CompatibleArraySchema>(307);
        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).Build();
        reader.Register<CompatibleListSchema>(307);

        CompatibleListSchema decoded = reader.Deserialize<CompatibleListSchema>(
            writer.Serialize(new CompatibleArraySchema { Values = [3, 4, 5] }));

        Assert.Equal([3, 4, 5], decoded.Values);
    }

    [Fact]
    public void CompatibleReadSupportsUInt32ListArrayFieldPairs()
    {
        ForyRuntime listWriter = ForyRuntime.Builder().Compatible(true).Build();
        listWriter.Register<CompatibleUInt32ListSchema>(309);
        ForyRuntime arrayReader = ForyRuntime.Builder().Compatible(true).Build();
        arrayReader.Register<CompatibleUInt32ArraySchema>(309);

        CompatibleUInt32ArraySchema decodedArray = arrayReader.Deserialize<CompatibleUInt32ArraySchema>(
            listWriter.Serialize(new CompatibleUInt32ListSchema { Values = [1u, uint.MaxValue] }));

        Assert.Equal([1u, uint.MaxValue], decodedArray.Values);

        ForyRuntime arrayListCarrierReader = ForyRuntime.Builder().Compatible(true).Build();
        arrayListCarrierReader.Register<CompatibleUInt32ArrayListCarrierSchema>(309);
        CompatibleUInt32ArrayListCarrierSchema decodedArrayListCarrier =
            arrayListCarrierReader.Deserialize<CompatibleUInt32ArrayListCarrierSchema>(
                listWriter.Serialize(new CompatibleUInt32ListSchema { Values = [7u, 8u] }));

        Assert.Equal([7u, 8u], decodedArrayListCarrier.Values);

        ForyRuntime arrayWriter = ForyRuntime.Builder().Compatible(true).Build();
        arrayWriter.Register<CompatibleUInt32ArraySchema>(310);
        ForyRuntime listReader = ForyRuntime.Builder().Compatible(true).Build();
        listReader.Register<CompatibleUInt32ListSchema>(310);

        CompatibleUInt32ListSchema decodedList = listReader.Deserialize<CompatibleUInt32ListSchema>(
            arrayWriter.Serialize(new CompatibleUInt32ArraySchema { Values = [9u, uint.MaxValue] }));

        Assert.Equal([9u, uint.MaxValue], decodedList.Values);
    }

    [Fact]
    public void CompatibleReadSupportsBinaryUint8ArrayPairs()
    {
        ForyRuntime binaryWriter = ForyRuntime.Builder().Compatible(true).Build();
        binaryWriter.Register<CompatibleBinarySchema>(311);
        ForyRuntime uint8ArrayReader = ForyRuntime.Builder().Compatible(true).Build();
        uint8ArrayReader.Register<CompatibleUInt8ArrayBytesSchema>(311);

        byte[] binaryPayload = [0, 1, 2, 250, 255];
        CompatibleUInt8ArrayBytesSchema decodedArray =
            uint8ArrayReader.Deserialize<CompatibleUInt8ArrayBytesSchema>(
                binaryWriter.Serialize(new CompatibleBinarySchema { Value = binaryPayload }));
        Assert.Equal(binaryPayload, decodedArray.Value);

        ForyRuntime listReader = ForyRuntime.Builder().Compatible(true).Build();
        listReader.Register<CompatibleUInt8ArrayListSchema>(311);
        CompatibleUInt8ArrayListSchema decodedList =
            listReader.Deserialize<CompatibleUInt8ArrayListSchema>(
                binaryWriter.Serialize(new CompatibleBinarySchema { Value = binaryPayload }));
        Assert.Equal(binaryPayload, decodedList.Value);

        ForyRuntime uint8ArrayWriter = ForyRuntime.Builder().Compatible(true).Build();
        uint8ArrayWriter.Register<CompatibleUInt8ArrayBytesSchema>(312);
        ForyRuntime binaryReader = ForyRuntime.Builder().Compatible(true).Build();
        binaryReader.Register<CompatibleBinarySchema>(312);

        byte[] arrayPayload = [3, 4, 5, 253];
        CompatibleBinarySchema decodedBinary = binaryReader.Deserialize<CompatibleBinarySchema>(
            uint8ArrayWriter.Serialize(new CompatibleUInt8ArrayBytesSchema { Value = arrayPayload }));
        Assert.Equal(arrayPayload, decodedBinary.Value);
    }

    [Fact]
    public void CompatibleReadAllowsNullableListSchemaIntoArrayCarrier()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).Build();
        writer.Register<CompatibleNullableListSchema>(308);
        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).Build();
        reader.Register<CompatibleArraySchema>(308);

        byte[] payload = writer.Serialize(new CompatibleNullableListSchema { Values = [1, 2] });
        CompatibleArraySchema decoded = reader.Deserialize<CompatibleArraySchema>(payload);
        Assert.Equal([1, 2], decoded.Values);

        byte[] nullPayload = writer.Serialize(new CompatibleNullableListSchema { Values = [1, null, 3] });
        InvalidDataException exception =
            Assert.Throws<InvalidDataException>(() => reader.Deserialize<CompatibleArraySchema>(nullPayload));
        Assert.Contains("non-null elements", exception.Message);
    }

    [Fact]
    public void RejectsNestedListArrayPairs()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(
                null,
                "values",
                new TypeMetaFieldType(
                    (uint)TypeId.Map,
                    false,
                    false,
                    [
                        new TypeMetaFieldType((uint)TypeId.String, false),
                        new TypeMetaFieldType((uint)TypeId.Int32Array, false),
                    ])),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [
                new TypeMetaFieldInfo(
                    null,
                    "values",
                    new TypeMetaFieldType(
                        (uint)TypeId.Map,
                        false,
                        false,
                        [
                            new TypeMetaFieldType((uint)TypeId.String, false),
                            new TypeMetaFieldType(
                                (uint)TypeId.List,
                                false,
                                false,
                                [new TypeMetaFieldType((uint)TypeId.VarInt32, false)]),
                        ])),
            ]);

        InvalidDataException exception =
            Assert.Throws<InvalidDataException>(() => TypeMeta.AssignFieldIds(remoteTypeMeta, localFields));
        Assert.Contains("remote and local field schemas are not compatible", exception.Message);
    }

    [Fact]
    public void AllowsNestedScalarNullableChanges()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(
                null,
                "values",
                new TypeMetaFieldType(
                    (uint)TypeId.List,
                    false,
                    false,
                    [new TypeMetaFieldType((uint)TypeId.String, false)])),
        ];

        TypeMeta remoteNullableTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [
                new TypeMetaFieldInfo(
                    null,
                    "values",
                    new TypeMetaFieldType(
                        (uint)TypeId.List,
                        false,
                        false,
                        [new TypeMetaFieldType((uint)TypeId.String, true)])),
            ]);

        TypeMeta.AssignFieldIds(remoteNullableTypeMeta, localFields);
        Assert.Equal(1, remoteNullableTypeMeta.Fields[0].AssignedFieldId);

        TypeMeta remoteTrackedTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [
                new TypeMetaFieldInfo(
                    null,
                    "values",
                    new TypeMetaFieldType(
                        (uint)TypeId.List,
                        false,
                        false,
                        [new TypeMetaFieldType((uint)TypeId.String, false, true)])),
            ]);

        Assert.Throws<InvalidDataException>(() => TypeMeta.AssignFieldIds(remoteTrackedTypeMeta, localFields));
    }

    [Fact]
    public void RestrictsByteSequenceCompatibility()
    {
        List<TypeMetaFieldInfo> localBytes =
        [
            new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.Binary, false)),
        ];
        TypeMeta remoteUInt8Array = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.UInt8Array, false))]);

        TypeMeta.AssignFieldIds(remoteUInt8Array, localBytes);
        Assert.Equal(1, remoteUInt8Array.Fields[0].AssignedFieldId);

        TypeMeta remoteInt8Array = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Int8Array, false))]);

        Assert.Throws<InvalidDataException>(() => TypeMeta.AssignFieldIds(remoteInt8Array, localBytes));

        List<TypeMetaFieldInfo> localNested =
        [
            new TypeMetaFieldInfo(
                null,
                "value",
                new TypeMetaFieldType(
                    (uint)TypeId.List,
                    false,
                    false,
                    [new TypeMetaFieldType((uint)TypeId.Binary, false)])),
        ];
        TypeMeta remoteNested = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [
                new TypeMetaFieldInfo(
                    null,
                    "value",
                    new TypeMetaFieldType(
                        (uint)TypeId.List,
                        false,
                        false,
                        [new TypeMetaFieldType((uint)TypeId.UInt8Array, false)])),
            ]);

        Assert.Throws<InvalidDataException>(() => TypeMeta.AssignFieldIds(remoteNested, localNested));
    }

    [Fact]
    public void RejectsFramedListArrayPairs()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(1, "values", new TypeMetaFieldType((uint)TypeId.Int32Array, false)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [
                new TypeMetaFieldInfo(
                    1,
                    "$tag1",
                    new TypeMetaFieldType(
                        (uint)TypeId.List,
                        true,
                        false,
                        [new TypeMetaFieldType((uint)TypeId.VarInt32, false)])),
            ]);

        Assert.Throws<InvalidDataException>(() => TypeMeta.AssignFieldIds(remoteTypeMeta, localFields));
    }

    [Fact]
    public void CompatibleScalarBoolString()
    {
        Assert.False(CompatibleRead<ScalarStringField, ScalarBoolField>(
            new ScalarStringField { Value = "0" }).Value);
        Assert.True(CompatibleRead<ScalarStringField, ScalarBoolField>(
            new ScalarStringField { Value = "1" }).Value);
        Assert.False(CompatibleRead<ScalarStringField, ScalarBoolField>(
            new ScalarStringField { Value = "false" }).Value);
        Assert.True(CompatibleRead<ScalarStringField, ScalarBoolField>(
            new ScalarStringField { Value = "true" }).Value);

        Assert.Equal("true", CompatibleRead<ScalarBoolField, ScalarStringField>(
            new ScalarBoolField { Value = true }).Value);
        Assert.Equal("false", CompatibleRead<ScalarBoolField, ScalarStringField>(
            new ScalarBoolField { Value = false }).Value);

        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarStringField, ScalarBoolField>(
            new ScalarStringField { Value = "TRUE" }));
    }

    [Fact]
    public void CompatibleScalarBoolNumber()
    {
        Assert.False(CompatibleRead<ScalarInt32Field, ScalarBoolField>(
            new ScalarInt32Field { Value = 0 }).Value);
        Assert.True(CompatibleRead<ScalarInt32Field, ScalarBoolField>(
            new ScalarInt32Field { Value = 1 }).Value);
        Assert.Equal(1, CompatibleRead<ScalarBoolField, ScalarInt32Field>(
            new ScalarBoolField { Value = true }).Value);
        Assert.Equal(0u, CompatibleRead<ScalarBoolField, ScalarUInt32Field>(
            new ScalarBoolField { Value = false }).Value);

        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarInt32Field, ScalarBoolField>(
            new ScalarInt32Field { Value = 2 }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarFloat64Field, ScalarBoolField>(
            new ScalarFloat64Field { Value = 0.5d }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarFloat64Field, ScalarBoolField>(
            new ScalarFloat64Field { Value = double.NaN }));
    }

    [Fact]
    public void CompatibleScalarNumberBounds()
    {
        Assert.Equal((short)123, CompatibleRead<ScalarInt32Field, ScalarInt16Field>(
            new ScalarInt32Field { Value = 123 }).Value);
        Assert.Equal(123L, CompatibleRead<ScalarInt32Field, ScalarInt64Field>(
            new ScalarInt32Field { Value = 123 }).Value);
        Assert.Equal((long)int.MaxValue, CompatibleRead<ScalarVarInt32Field, ScalarInt64Field>(
            new ScalarVarInt32Field { Value = int.MaxValue }).Value);
        Assert.Equal(1, CompatibleRead<ScalarFloat64Field, ScalarInt32Field>(
            new ScalarFloat64Field { Value = 1.0d }).Value);
        Assert.Equal(double.PositiveInfinity, CompatibleRead<ScalarFloat32Field, ScalarFloat64Field>(
            new ScalarFloat32Field { Value = float.PositiveInfinity }).Value);

        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarInt32Field, ScalarInt16Field>(
            new ScalarInt32Field { Value = 40_000 }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarFloat64Field, ScalarInt32Field>(
            new ScalarFloat64Field { Value = 1.5d }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarInt32Field, ScalarFloat32Field>(
            new ScalarInt32Field { Value = 16_777_217 }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarFloat32Field, ScalarFloat64Field>(
            new ScalarFloat32Field { Value = float.NaN }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarUInt64Field, ScalarInt64Field>(
            new ScalarUInt64Field { Value = ulong.MaxValue }));
    }

    [Fact]
    public void CompatibleScalarStringNumber()
    {
        Assert.Equal(100, CompatibleRead<ScalarStringField, ScalarInt32Field>(
            new ScalarStringField { Value = "1e2" }).Value);
        Assert.Equal(0.5d, CompatibleRead<ScalarStringField, ScalarFloat64Field>(
            new ScalarStringField { Value = "0.5" }).Value);

        double negativeZero = CompatibleRead<ScalarStringField, ScalarFloat64Field>(
            new ScalarStringField { Value = "-0" }).Value;
        Assert.Equal(BitConverter.DoubleToUInt64Bits(-0.0d), BitConverter.DoubleToUInt64Bits(negativeZero));

        Assert.Equal("-12", CompatibleRead<ScalarInt32Field, ScalarStringField>(
            new ScalarInt32Field { Value = -12 }).Value);
        Assert.Equal("125.0", CompatibleRead<ScalarFloat64Field, ScalarStringField>(
            new ScalarFloat64Field { Value = 125.0d }).Value);
        Assert.Equal("-0.0", CompatibleRead<ScalarFloat64Field, ScalarStringField>(
            new ScalarFloat64Field { Value = -0.0d }).Value);

        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarStringField, ScalarInt32Field>(
            new ScalarStringField { Value = "1.5" }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarStringField, ScalarFloat32Field>(
            new ScalarStringField { Value = "0.1" }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarStringField, ScalarInt32Field>(
            new ScalarStringField { Value = "+1" }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarFloat64Field, ScalarStringField>(
            new ScalarFloat64Field { Value = double.PositiveInfinity }));
    }

    [Fact]
    public void CompatibleScalarDecimal()
    {
        Assert.True(CompatibleRead<ScalarDecimalField, ScalarBoolField>(
            new ScalarDecimalField { Value = new ForyDecimal(new BigInteger(10), 1) }).Value);
        Assert.Equal(new ForyDecimal(BigInteger.One, 0), CompatibleRead<ScalarBoolField, ScalarDecimalField>(
            new ScalarBoolField { Value = true }).Value);
        Assert.Equal(new ForyDecimal(new BigInteger(1234), 2), CompatibleRead<ScalarStringField, ScalarDecimalField>(
            new ScalarStringField { Value = "12.340" }).Value);
        Assert.Equal("12.34", CompatibleRead<ScalarDecimalField, ScalarStringField>(
            new ScalarDecimalField { Value = new ForyDecimal(new BigInteger(12340), 3) }).Value);

        string digits256 = new('1', 256);
        ForyDecimal digitBound = CompatibleRead<ScalarStringField, ScalarDecimalField>(
            new ScalarStringField { Value = digits256 }).Value;
        Assert.Equal(BigInteger.Parse(digits256), digitBound.UnscaledValue);
        Assert.Equal(0, digitBound.Scale);

        ForyDecimal exponentBound = CompatibleRead<ScalarStringField, ScalarDecimalField>(
            new ScalarStringField { Value = "1e255" }).Value;
        Assert.Equal(256, exponentBound.UnscaledValue.ToString().Length);
        Assert.Equal(0, exponentBound.Scale);

        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarDecimalField, ScalarBoolField>(
            new ScalarDecimalField { Value = new ForyDecimal(new BigInteger(5), 1) }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarDecimalField, ScalarInt32Field>(
            new ScalarDecimalField { Value = new ForyDecimal(new BigInteger(5), 1) }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarStringField, ScalarDecimalField>(
            new ScalarStringField { Value = new string('1', 257) }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarStringField, ScalarDecimalField>(
            new ScalarStringField { Value = "0." + new string('0', 319) }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarStringField, ScalarDecimalField>(
            new ScalarStringField { Value = "1e1000000" }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarStringField, ScalarDecimalField>(
            new ScalarStringField { Value = "1e256" }));
        Assert.Throws<InvalidDataException>(() => CompatibleRead<ScalarDecimalField, ScalarStringField>(
            new ScalarDecimalField { Value = new ForyDecimal(BigInteger.One, -256) }));

        InvalidDataException expansionError = Assert.Throws<InvalidDataException>(() =>
            CompatibleRead<ScalarFloat64Field, ScalarDecimalField>(
                new ScalarFloat64Field { Value = double.Epsilon }));
        Assert.Contains("field 'value' from Float64 to Decimal", expansionError.Message);
    }

    [Fact]
    public void CompatibleScalarNullable()
    {
        Assert.True(CompatibleRead<ScalarStringField, ScalarBoolField>(
            new ScalarStringField { Value = "1" }).Value);
        Assert.False(CompatibleRead<ScalarStringField, ScalarBoolField>(
            new ScalarStringField { Value = null }).Value);
        Assert.Equal(1, CompatibleRead<ScalarBoolField, ScalarNullableInt32Field>(
            new ScalarBoolField { Value = true }).Value);
        Assert.Equal(1L, CompatibleRead<ScalarBoolField, ScalarNullableInt64Field>(
            new ScalarBoolField { Value = true }).Value);
        Assert.Null(CompatibleRead<ScalarNullableInt32Field, ScalarNullableInt64Field>(
            new ScalarNullableInt32Field { Value = null }).Value);
    }

    [Fact]
    public void CompatibleScalarRejectsInvalidBoolPayload()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).Build();
        writer.Register<ScalarBoolField>(813);
        byte[] payload = writer.Serialize(new ScalarBoolField { Value = true });
        payload[^1] = 2;

        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).Build();
        reader.Register<ScalarNullableBoolField>(813);
        Assert.Throws<InvalidDataException>(() => reader.Deserialize<ScalarNullableBoolField>(payload));
    }

    [Fact]
    public void CompatibleScalarSchemaOverride()
    {
        Assert.Equal(4_000_000_000u, CompatibleRead<ScalarFixedUInt32Field, ScalarUInt32Field>(
            new ScalarFixedUInt32Field { Value = 4_000_000_000u }).Value);
        Assert.Equal(4_000_000_000u, CompatibleRead<ScalarFixedUInt32Field, ScalarNullableFixedUInt32Field>(
            new ScalarFixedUInt32Field { Value = 4_000_000_000u }).Value);
    }

    [Fact]
    public void CompatibleScalarTrackingRefRules()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.Bool, false)),
        ];

        TypeMeta remoteTrackingTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.String, false, true))]);
        Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteTrackingTypeMeta, localFields));

        List<TypeMetaFieldInfo> localTrackingFields =
        [
            new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.Bool, false, true)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.String, false))]);
        Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteTypeMeta, localTrackingFields));

        TypeMeta remoteBoolTrackingTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Bool, false, true))]);
        Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteBoolTrackingTypeMeta, localFields));

        TypeMeta remoteBoolTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Bool, false))]);
        Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteBoolTypeMeta, localTrackingFields));

        TypeMeta remoteBoolBothTrackingTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Bool, false, true))]);
        TypeMeta.AssignFieldIds(remoteBoolBothTrackingTypeMeta, localTrackingFields);
        Assert.Equal(0, remoteBoolBothTrackingTypeMeta.Fields[0].AssignedFieldId);

        List<TypeMetaFieldInfo> localNullableTrackingFields =
        [
            new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.Bool, true, true)),
        ];
        TypeMeta remoteBoolNullableTrackingTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Bool, true, true))]);
        TypeMeta.AssignFieldIds(remoteBoolNullableTrackingTypeMeta, localNullableTrackingFields);
        Assert.Equal(0, remoteBoolNullableTrackingTypeMeta.Fields[0].AssignedFieldId);

        TypeMeta remoteBoolTrackingNullableTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Bool, true, true))]);
        Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteBoolTrackingNullableTypeMeta, localTrackingFields));

        TypeMeta remoteBoolTrackingRequiredTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Bool, false, true))]);
        Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteBoolTrackingRequiredTypeMeta, localNullableTrackingFields));

        List<TypeMetaFieldInfo> localTrackingUIntFields =
        [
            new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.VarUInt32, false, true)),
        ];
        TypeMeta remoteFixedUIntTrackingTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            0,
            MetaString.Empty('_', '_'),
            new MetaString("remote", MetaStringEncoding.Utf8, '_', '_', "remote"u8.ToArray()),
            false,
            [new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.UInt32, false, true))]);
        Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteFixedUIntTrackingTypeMeta, localTrackingUIntFields));
    }

    [Fact]
    public void NestedScalarConversionRejected()
    {
        Assert.Throws<InvalidDataException>(
            () => CompatibleRead<ScalarStringListField, ScalarInt32ListField>(
                new ScalarStringListField { Values = ["1"] }));
    }

    [Fact]
    public void SameSchemaScalarRoundTrip()
    {
        Assert.True(CompatibleRead<ScalarBoolField, ScalarBoolField>(
            new ScalarBoolField { Value = true }).Value);
        Assert.True(double.IsNaN(CompatibleRead<ScalarFloat64Field, ScalarFloat64Field>(
            new ScalarFloat64Field { Value = double.NaN }).Value));
    }

    [Fact]
    public void NestedSchemaAnnotationControlsMapAndListPayload()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<NestedSchemaByName>(303);

        NestedSchemaByName value = new()
        {
            Values =
            {
                [4_000_000_000u] = [7UL, 1_000_000_000UL, null],
                [3u] = [42UL],
            },
        };

        NestedSchemaByName decoded = fory.Deserialize<NestedSchemaByName>(fory.Serialize(value));
        Assert.Equal(value.Values.Count, decoded.Values.Count);
        Assert.Equal(value.Values[4_000_000_000u], decoded.Values[4_000_000_000u]);
        Assert.Equal(value.Values[3u], decoded.Values[3u]);
    }

    [Fact]
    public void CompatibleSkipUsesRemoteNestedSchemaMetadata()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).Build();
        writer.Register<NestedSchemaSkipWriter>(304);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).Build();
        reader.Register<NestedSchemaSkipReader>(304);

        NestedSchemaSkipWriter value = new()
        {
            Values =
            {
                [4_000_000_000u] = [7UL, 1_000_000_000UL],
                [3u] = [42UL],
            },
            Tail = 99,
        };

        NestedSchemaSkipReader decoded = reader.Deserialize<NestedSchemaSkipReader>(writer.Serialize(value));
        Assert.Equal(99, decoded.Tail);
    }

    [Fact]
    public void TaggedUnsignedFieldUsesCompactBoundary()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<SchemaNumbers>(301);

        SchemaNumbers compact = new()
        {
            U32Fixed = 0x11223344u,
            U64Tagged = (ulong)int.MaxValue,
        };
        SchemaNumbers wide = new()
        {
            U32Fixed = 0x11223344u,
            U64Tagged = (ulong)int.MaxValue + 1UL,
        };

        byte[] compactPayload = fory.Serialize(compact);
        byte[] widePayload = fory.Serialize(wide);

        Assert.Equal(5, widePayload.Length - compactPayload.Length);
        Assert.Equal(compact.U64Tagged, fory.Deserialize<SchemaNumbers>(compactPayload).U64Tagged);
        Assert.Equal(wide.U64Tagged, fory.Deserialize<SchemaNumbers>(widePayload).U64Tagged);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void UnsignedFieldsRoundTrip(bool compatible)
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(compatible).Build();
        fory.Register<UnsignedFields>(302);

        UnsignedFields highValues = new()
        {
            U8 = byte.MaxValue,
            U16 = ushort.MaxValue,
            U32 = uint.MaxValue,
            U64 = ulong.MaxValue,
            U8Nullable = 128,
            U16Nullable = 40_000,
            U32Nullable = 4_000_000_000u,
            U64Nullable = ulong.MaxValue - 7,
        };
        AssertUnsignedEqual(highValues, fory.Deserialize<UnsignedFields>(fory.Serialize(highValues)));

        UnsignedFields nullablesMissing = new()
        {
            U8 = 0,
            U16 = 1,
            U32 = 2,
            U64 = 3,
            U8Nullable = null,
            U16Nullable = null,
            U32Nullable = null,
            U64Nullable = null,
        };
        AssertUnsignedEqual(nullablesMissing, fory.Deserialize<UnsignedFields>(fory.Serialize(nullablesMissing)));
    }

    [Fact]
    public void CompatibleSchemaEvolutionRoundTrip()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).Build();
        writer.Register<OneStringField>(200);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).Build();
        reader.Register<TwoStringField>(200);

        OneStringField source = new() { F1 = "hello" };
        byte[] payload = writer.Serialize(source);
        TwoStringField evolved = reader.Deserialize<TwoStringField>(payload);

        Assert.Equal("hello", evolved.F1);
        Assert.Equal(string.Empty, evolved.F2);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void CompatibleSchemaEvolutionRoundTripForListElements(bool trackRef)
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).TrackRef(trackRef).Build();
        writer.Register<OneStringField>(200);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).TrackRef(trackRef).Build();
        reader.Register<TwoStringField>(200);

        List<OneStringField?> source =
        [
            new OneStringField { F1 = "hello" },
            null,
            new OneStringField { F1 = "world" },
        ];

        byte[] payload = writer.Serialize(source);
        List<TwoStringField?> evolved = reader.Deserialize<List<TwoStringField?>>(payload);

        Assert.Equal(3, evolved.Count);
        TwoStringField first = Assert.IsType<TwoStringField>(evolved[0]);
        Assert.Equal("hello", first.F1);
        Assert.Equal(string.Empty, first.F2);
        Assert.Null(evolved[1]);
        TwoStringField third = Assert.IsType<TwoStringField>(evolved[2]);
        Assert.Equal("world", third.F1);
        Assert.Equal(string.Empty, third.F2);

        first.F2 = "extra-first";
        third.F2 = "extra-third";
        List<OneStringField?> roundTripped = writer.Deserialize<List<OneStringField?>>(reader.Serialize(evolved));

        Assert.Equal(3, roundTripped.Count);
        OneStringField firstRound = Assert.IsType<OneStringField>(roundTripped[0]);
        Assert.Equal("hello", firstRound.F1);
        Assert.Null(roundTripped[1]);
        OneStringField thirdRound = Assert.IsType<OneStringField>(roundTripped[2]);
        Assert.Equal("world", thirdRound.F1);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void CompatibleSchemaEvolutionRoundTripForNestedListField(bool trackRef)
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).TrackRef(trackRef).Build();
        writer.Register<OneStringField>(200);
        writer.Register<OneStringFieldListHolder>(201);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).TrackRef(trackRef).Build();
        reader.Register<TwoStringField>(200);
        reader.Register<TwoStringFieldListHolder>(201);

        OneStringFieldListHolder source = new()
        {
            Items =
            [
                new OneStringField { F1 = "hello" },
                null,
                new OneStringField { F1 = "world" },
            ],
        };

        TwoStringFieldListHolder evolved = reader.Deserialize<TwoStringFieldListHolder>(writer.Serialize(source));
        Assert.Equal(3, evolved.Items.Count);
        TwoStringField first = Assert.IsType<TwoStringField>(evolved.Items[0]);
        Assert.Equal("hello", first.F1);
        Assert.Equal(string.Empty, first.F2);
        Assert.Null(evolved.Items[1]);
        TwoStringField third = Assert.IsType<TwoStringField>(evolved.Items[2]);
        Assert.Equal("world", third.F1);
        Assert.Equal(string.Empty, third.F2);

        first.F2 = "extra-first";
        third.F2 = "extra-third";
        OneStringFieldListHolder roundTripped = writer.Deserialize<OneStringFieldListHolder>(reader.Serialize(evolved));
        Assert.Equal(3, roundTripped.Items.Count);
        OneStringField firstRound = Assert.IsType<OneStringField>(roundTripped.Items[0]);
        Assert.Equal("hello", firstRound.F1);
        Assert.Null(roundTripped.Items[1]);
        OneStringField thirdRound = Assert.IsType<OneStringField>(roundTripped.Items[2]);
        Assert.Equal("world", thirdRound.F1);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void CompatibleSchemaEvolutionRoundTripForMapValues(bool trackRef)
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).TrackRef(trackRef).Build();
        writer.Register<OneStringField>(200);
        writer.Register<OneStringFieldMapHolder>(203);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).TrackRef(trackRef).Build();
        reader.Register<TwoStringField>(200);
        reader.Register<TwoStringFieldMapHolder>(203);

        OneStringFieldMapHolder source = new()
        {
            Items = new Dictionary<string, OneStringField?>
            {
                ["first"] = new OneStringField { F1 = "hello" },
                ["second"] = null,
                ["third"] = new OneStringField { F1 = "world" },
            },
        };

        TwoStringFieldMapHolder evolved = reader.Deserialize<TwoStringFieldMapHolder>(writer.Serialize(source));
        Assert.Equal(3, evolved.Items.Count);
        TwoStringField first = Assert.IsType<TwoStringField>(evolved.Items["first"]);
        Assert.Equal("hello", first.F1);
        Assert.Equal(string.Empty, first.F2);
        Assert.Null(evolved.Items["second"]);
        TwoStringField third = Assert.IsType<TwoStringField>(evolved.Items["third"]);
        Assert.Equal("world", third.F1);
        Assert.Equal(string.Empty, third.F2);

        first.F2 = "extra-first";
        third.F2 = "extra-third";
        OneStringFieldMapHolder roundTripped = writer.Deserialize<OneStringFieldMapHolder>(reader.Serialize(evolved));
        Assert.Equal(3, roundTripped.Items.Count);
        OneStringField firstRound = Assert.IsType<OneStringField>(roundTripped.Items["first"]);
        Assert.Equal("hello", firstRound.F1);
        Assert.Null(roundTripped.Items["second"]);
        OneStringField thirdRound = Assert.IsType<OneStringField>(roundTripped.Items["third"]);
        Assert.Equal("world", thirdRound.F1);
    }

    [Fact]
    public void SchemaVersionMismatchThrows()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(false).CheckStructVersion(true).Build();
        writer.Register<OneStringField>(200);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(false).CheckStructVersion(true).Build();
        reader.Register<TwoStringField>(200);

        byte[] payload = writer.Serialize(new OneStringField { F1 = "hello" });
        Assert.Throws<InvalidDataException>(() => { _ = reader.Deserialize<TwoStringField>(payload); });
    }

    [Fact]
    public void SourceGeneratedUnionRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(false).Build();
        fory.Register<SourceGeneratedShape>(302);
        fory.Register<SourceGeneratedUnionHolder>(303);

        SourceGeneratedUnionHolder known = new()
        {
            Shape = new SourceGeneratedShape.Number(42),
        };
        SourceGeneratedUnionHolder knownZero = new()
        {
            Shape = new SourceGeneratedShape.Text("zero"),
        };
        SourceGeneratedUnionHolder unknown = new()
        {
            Shape = new SourceGeneratedShape.Unknown(new UnknownCase(99, "future")),
        };

        SourceGeneratedUnionHolder knownDecoded =
            fory.Deserialize<SourceGeneratedUnionHolder>(fory.Serialize(known));
        SourceGeneratedUnionHolder knownZeroDecoded =
            fory.Deserialize<SourceGeneratedUnionHolder>(fory.Serialize(knownZero));
        SourceGeneratedUnionHolder unknownDecoded =
            fory.Deserialize<SourceGeneratedUnionHolder>(fory.Serialize(unknown));

        SourceGeneratedShape.Number number = Assert.IsType<SourceGeneratedShape.Number>(knownDecoded.Shape);
        Assert.Equal(42, number.Value);
        SourceGeneratedShape.Text text = Assert.IsType<SourceGeneratedShape.Text>(knownZeroDecoded.Shape);
        Assert.Equal("zero", text.Value);
        SourceGeneratedShape.Unknown unknownCase =
            Assert.IsType<SourceGeneratedShape.Unknown>(unknownDecoded.Shape);
        Assert.Equal(99, unknownCase.Value.CaseId);
        Assert.Equal("future", unknownCase.Value.Value);
    }

    [Fact]
    public void IdUnionDoesNotUseTypeMetaLimits()
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Compatible(true)
            .MaxTypeMetaBytes(1)
            .MaxSchemaVersionsPerType(1)
            .Build();
        fory.Register<SourceGeneratedShape>(904);

        SourceGeneratedShape value = new SourceGeneratedShape.Number(42);
        SourceGeneratedShape decoded =
            fory.Deserialize<SourceGeneratedShape>(fory.Serialize(value));

        SourceGeneratedShape.Number number = Assert.IsType<SourceGeneratedShape.Number>(decoded);
        Assert.Equal(42, number.Value);
    }

    [Fact]
    public void UnionFieldRoundTripCompatible()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(true).Build();
        fory.Register<StructWithUnion2>(301);

        StructWithUnion2 first = new() { Union = Union2<string, long>.OfT1("hello") };
        StructWithUnion2 second = new() { Union = Union2<string, long>.OfT2(42L) };

        StructWithUnion2 firstDecoded = fory.Deserialize<StructWithUnion2>(fory.Serialize(first));
        StructWithUnion2 secondDecoded = fory.Deserialize<StructWithUnion2>(fory.Serialize(second));

        Assert.Equal(0, firstDecoded.Union.Index);
        Assert.Equal("hello", firstDecoded.Union.GetT1());
        Assert.Equal(1, secondDecoded.Union.Index);
        Assert.Equal(42L, secondDecoded.Union.GetT2());
    }

    [Fact]
    public void Union2UsesZeroBasedWireCaseIds()
    {
        TypeResolver resolver = new();
        Serializer<Union2<string, long>> serializer = resolver.GetSerializer<Union2<string, long>>();
        Config config = ForyRuntime.Builder().TrackRef(true).Build().Config;

        ByteWriter firstWriter = new();
        WriteContext firstWrite = new(firstWriter, resolver, trackRef: true);
        serializer.WriteData(firstWrite, Union2<string, long>.OfT1("hello"), hasGenerics: false);
        ByteReader firstReader = new(firstWriter.ToArray());
        Assert.Equal(0u, firstReader.ReadVarUInt32());

        Union2<string, long> firstDecoded =
            serializer.ReadData(new ReadContext(new ByteReader(firstWriter.ToArray()), resolver, config));
        Assert.Equal(0, firstDecoded.Index);
        Assert.Equal("hello", firstDecoded.GetT1());

        ByteWriter secondWriter = new();
        WriteContext secondWrite = new(secondWriter, resolver, trackRef: true);
        serializer.WriteData(secondWrite, Union2<string, long>.OfT2(42L), hasGenerics: false);
        ByteReader secondReader = new(secondWriter.ToArray());
        Assert.Equal(1u, secondReader.ReadVarUInt32());

        Union2<string, long> secondDecoded =
            serializer.ReadData(new ReadContext(new ByteReader(secondWriter.ToArray()), resolver, config));
        Assert.Equal(1, secondDecoded.Index);
        Assert.Equal(42L, secondDecoded.GetT2());

    }

    [Fact]
    public void EnumRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<TestColor>(100);
        fory.Register<StructWithEnum>(101);

        StructWithEnum value = new() { Name = "enum", Color = TestColor.Blue, Value = 42 };
        StructWithEnum decoded = fory.Deserialize<StructWithEnum>(fory.Serialize(value));
        Assert.Equal(value.Name, decoded.Name);
        Assert.Equal(value.Color, decoded.Color);
        Assert.Equal(value.Value, decoded.Value);
    }

    [Fact]
    public void EnumRoundTripPreservesUndefinedValue()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<TestColor>(100);

        TestColor value = (TestColor)12345;
        TestColor decoded = fory.Deserialize<TestColor>(fory.Serialize(value));
        Assert.Equal(value, decoded);
        Assert.Equal(12345u, Convert.ToUInt32(decoded));
    }

    [Fact]
    public void DynamicObjectSupportsObjectKeyMapAndSet()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        Dictionary<object, object?> map = new()
        {
            ["k1"] = 7,
            [2] = "v2",
            [true] = null,
        };
        Dictionary<object, object?> mapDecoded =
            Assert.IsType<Dictionary<object, object?>>(fory.Deserialize<object?>(fory.Serialize<object?>(map)));
        Assert.Equal(3, mapDecoded.Count);
        Assert.Equal(7, mapDecoded["k1"]);
        Assert.Equal("v2", mapDecoded[2]);
        Assert.True(mapDecoded.ContainsKey(true));
        Assert.Null(mapDecoded[true]);

        HashSet<object> set = ["a", 7, false];
        HashSet<object?> setDecoded =
            Assert.IsType<HashSet<object?>>(fory.Deserialize<object?>(fory.Serialize<object?>(set)));
        Assert.Equal(3, setDecoded.Count);
        Assert.Contains("a", setDecoded);
        Assert.Contains(7, setDecoded);
        Assert.Contains(false, setDecoded);
    }

    [Fact]
    public void DynamicObjectCompatibleModeSupportsStructKeyAndValue()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(true).Build();
        fory.Register<EvolvingOverrideValue>(410);
        fory.Register<FixedOverrideValue>(411);

        Dictionary<object, object?> source = new()
        {
            [new FixedOverrideValue { F1 = "key" }] = new EvolvingOverrideValue { F1 = "value" },
        };

        Dictionary<object, object?> decoded =
            Assert.IsType<Dictionary<object, object?>>(fory.Deserialize<object?>(fory.Serialize<object?>(source)));
        KeyValuePair<object, object?> entry = Assert.Single(decoded);
        FixedOverrideValue key = Assert.IsType<FixedOverrideValue>(entry.Key);
        EvolvingOverrideValue value = Assert.IsType<EvolvingOverrideValue>(entry.Value);
        Assert.Equal("key", key.F1);
        Assert.Equal("value", value.F1);
    }

    [Fact]
    public void DynamicObjectSupportsExtendedCollectionContainers()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        Queue<object?> queue = new();
        queue.Enqueue("q1");
        queue.Enqueue(7);
        queue.Enqueue(null);
        List<object?> queueDecoded = Assert.IsType<List<object?>>(fory.Deserialize<object?>(fory.Serialize<object?>(queue)));
        Assert.Equal(new object?[] { "q1", 7, null }, queueDecoded.ToArray());

        Stack<object?> stack = new();
        stack.Push("s1");
        stack.Push(9);
        List<object?> stackDecoded = Assert.IsType<List<object?>>(fory.Deserialize<object?>(fory.Serialize<object?>(stack)));
        Assert.Equal(new object?[] { 9, "s1" }, stackDecoded.ToArray());

        LinkedList<object?> linkedList = new(new object?[] { "l1", 3, null });
        List<object?> linkedListDecoded = Assert.IsType<List<object?>>(fory.Deserialize<object?>(fory.Serialize<object?>(linkedList)));
        Assert.Equal(new object?[] { "l1", 3, null }, linkedListDecoded.ToArray());

        ImmutableHashSet<object?> immutableSet = ImmutableHashSet.Create<object?>("i1", 5);
        HashSet<object?> immutableSetDecoded =
            Assert.IsType<HashSet<object?>>(fory.Deserialize<object?>(fory.Serialize<object?>(immutableSet)));
        Assert.Equal(2, immutableSetDecoded.Count);
        Assert.Contains("i1", immutableSetDecoded);
        Assert.Contains(5, immutableSetDecoded);
    }

    [Fact]
    public void DynamicObjectReadDepthExceededThrows()
    {
        ForyRuntime writer = ForyRuntime.Builder().Build();
        object? value = new List<object?> { new List<object?> { 1 } };
        byte[] payload = writer.Serialize<object?>(value);

        ForyRuntime reader = ForyRuntime.Builder().MaxDepth(1).Build();
        InvalidDataException ex = Assert.Throws<InvalidDataException>(() => reader.Deserialize<object?>(payload));
        Assert.Contains("dynamic object nesting depth", ex.Message);
    }

    [Fact]
    public void DynamicObjectReadDepthWithinLimitRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder().MaxDepth(2).Build();
        object? value = new List<object?> { new List<object?> { 1 } };

        List<object?> outer = Assert.IsType<List<object?>>(fory.Deserialize<object?>(fory.Serialize<object?>(value)));
        Assert.Single(outer);
        List<object?> inner = Assert.IsType<List<object?>>(outer[0]);
        Assert.Single(inner);
        Assert.Equal(1, inner[0]);
    }

    [Fact]
    public void UnknownCaseReadDepthExceededThrows()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(false).Build();
        writer.Register<SourceGeneratedShape>(304);
        writer.Register<SourceGeneratedUnionHolder>(305);
        SourceGeneratedUnionHolder source = new()
        {
            Shape = new SourceGeneratedShape.Unknown(
                new UnknownCase(99, new List<object?> { new List<object?> { 1 } })),
        };
        byte[] payload = writer.Serialize(source);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(false).MaxDepth(1).Build();
        reader.Register<SourceGeneratedShape>(304);
        reader.Register<SourceGeneratedUnionHolder>(305);
        InvalidDataException ex =
            Assert.Throws<InvalidDataException>(() => reader.Deserialize<SourceGeneratedUnionHolder>(payload));
        Assert.Contains("dynamic object nesting depth", ex.Message);
    }

    [Fact]
    public void UnknownCaseScalarReadDepthFree()
    {
        ForyRuntime fory = ForyRuntime.Builder().Compatible(false).MaxDepth(1).Build();
        fory.Register<SourceGeneratedShape>(306);
        fory.Register<SourceGeneratedUnionHolder>(307);
        SourceGeneratedUnionHolder source = new()
        {
            Shape = new SourceGeneratedShape.Unknown(new UnknownCase(99, 1)),
        };

        SourceGeneratedUnionHolder decoded =
            fory.Deserialize<SourceGeneratedUnionHolder>(fory.Serialize(source));
        SourceGeneratedShape.Unknown unknown = Assert.IsType<SourceGeneratedShape.Unknown>(decoded.Shape);
        Assert.Equal(99, unknown.Value.CaseId);
        Assert.Equal(1, unknown.Value.Value);
    }

    [Fact]
    public void GeneratedSerializerSupportsObjectKeyMap()
    {
        ForyRuntime fory = ForyRuntime.Builder().TrackRef(true).Build();
        fory.Register<DynamicAnyHolder>(400);

        DynamicAnyHolder source = new()
        {
            AnyValue = new Dictionary<object, object?>
            {
                ["inner"] = 9,
                [10] = "ten",
            },
            AnySet = ["x", 123],
            AnyMap = new Dictionary<object, object?>
            {
                ["key1"] = null,
                [99] = new List<object?> { "n", 1 },
            },
        };

        DynamicAnyHolder decoded = fory.Deserialize<DynamicAnyHolder>(fory.Serialize(source));
        Dictionary<object, object?> dynamicMap = Assert.IsType<Dictionary<object, object?>>(decoded.AnyValue);
        Assert.Equal(9, dynamicMap["inner"]);
        Assert.Equal("ten", dynamicMap[10]);
        Assert.Equal(source.AnySet.Count, decoded.AnySet.Count);
        Assert.Contains("x", decoded.AnySet);
        Assert.Contains(123, decoded.AnySet);
        Assert.Equal(source.AnyMap.Count, decoded.AnyMap.Count);
        Assert.True(decoded.AnyMap.ContainsKey("key1"));
        Assert.Null(decoded.AnyMap["key1"]);
        List<object?> nested = Assert.IsType<List<object?>>(decoded.AnyMap[99]);
        Assert.Equal("n", nested[0]);
        Assert.Equal(1, nested[1]);
    }

    [Fact]
    public void GeneratedSerializerPreservesSharedDynamicReferences()
    {
        ForyRuntime fory = ForyRuntime.Builder().TrackRef(true).Build();
        fory.Register<DynamicAnyHolder>(401);

        List<object?> shared = ["n", 1];
        DynamicAnyHolder source = new()
        {
            AnyValue = shared,
            AnySet = [shared],
            AnyMap = new Dictionary<object, object?>
            {
                ["first"] = shared,
                ["second"] = shared,
            },
        };

        DynamicAnyHolder decoded = fory.Deserialize<DynamicAnyHolder>(fory.Serialize(source));
        List<object?> anyValue = Assert.IsType<List<object?>>(decoded.AnyValue);
        List<object?> setItem = Assert.Single(decoded.AnySet.OfType<List<object?>>());
        List<object?> first = Assert.IsType<List<object?>>(decoded.AnyMap["first"]);
        List<object?> second = Assert.IsType<List<object?>>(decoded.AnyMap["second"]);

        Assert.Same(anyValue, setItem);
        Assert.Same(anyValue, first);
        Assert.Same(anyValue, second);
    }

    [Fact]
    public void CollectionRefTrackingPreservesSharedValues()
    {
        ForyRuntime fory = ForyRuntime.Builder().TrackRef(true).Build();
        fory.Register<Address>(402);

        Address shared = new() { Street = "Main", Zip = 94107 };

        List<Address?> list = [shared, shared, null];
        List<Address?> decodedList = fory.Deserialize<List<Address?>>(fory.Serialize(list));
        Assert.Same(decodedList[0], decodedList[1]);
        Assert.Null(decodedList[2]);

        Dictionary<string, Address> map = new()
        {
            ["left"] = shared,
            ["right"] = shared,
        };
        Dictionary<string, Address> decodedMap = fory.Deserialize<Dictionary<string, Address>>(fory.Serialize(map));
        Assert.Same(decodedMap["left"], decodedMap["right"]);
    }

    [Fact]
    public void StringSerializerUsesLatin1WhenAllCharsAreLatin1()
    {
        (ulong encoding, string decoded) = WriteAndReadString("Hello\u00E9\u00FF");
        Assert.Equal(StringEncodingLatin1, encoding);
        Assert.Equal("Hello\u00E9\u00FF", decoded);
    }

    [Fact]
    public void StringSerializerUsesUtf8WhenAsciiRatioIsHigh()
    {
        (ulong encoding, string decoded) = WriteAndReadString("abc\u4E16\u754C");
        Assert.Equal(StringEncodingUtf8, encoding);
        Assert.Equal("abc\u4E16\u754C", decoded);
    }

    [Fact]
    public void StringSerializerUsesUtf16WhenAsciiRatioIsLow()
    {
        (ulong encoding, string decoded) = WriteAndReadString("\u4F60\u597D\u4E16\u754Ca");
        Assert.Equal(StringEncodingUtf16, encoding);
        Assert.Equal("\u4F60\u597D\u4E16\u754Ca", decoded);
    }

    [Fact]
    public void StringSerializerValidatesBeyondSampleForLatin1()
    {
        string value = new string('a', 64) + "\u4E16";
        (ulong encoding, string decoded) = WriteAndReadString(value);
        Assert.Equal(StringEncodingUtf8, encoding);
        Assert.Equal(value, decoded);
    }

    [Fact]
    public void TypeResolverVersionHashIncludesUnregisteredTypeBindings()
    {
        TypeResolver resolver = new();
        ulong baselineHash = resolver.VersionHash();
        _ = resolver.GetTypeInfo<List<int>>();
        ulong boundHash = resolver.VersionHash();

        Assert.NotEqual(baselineHash, boundHash);
    }

    [Fact]
    public void TypeResolverVersionHashIsStableWithinSameFinalizedResolver()
    {
        TypeResolver resolver = new();
        _ = resolver.GetTypeInfo<List<int>>();
        _ = resolver.GetTypeInfo<Dictionary<string, int>>();

        ulong first = resolver.VersionHash();
        ulong second = resolver.VersionHash();

        Assert.Equal(first, second);
    }

    [Fact]
    public void TypeResolverVersionHashIsDeterministicForSameBoundTypes()
    {
        TypeResolver resolverA = new();
        _ = resolverA.GetTypeInfo<List<int>>();
        _ = resolverA.GetTypeInfo<Dictionary<string, int>>();
        ulong hashA = resolverA.VersionHash();

        TypeResolver resolverB = new();
        _ = resolverB.GetTypeInfo<List<int>>();
        _ = resolverB.GetTypeInfo<Dictionary<string, int>>();
        ulong hashB = resolverB.VersionHash();

        Assert.Equal(hashA, hashB);
    }

    [Fact]
    public void CompatibleStructFastPathValidatesEmbeddedTypeMetaTypeId()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).Build();
        writer.Register<OneStringField>(200);
        byte[] payload = writer.Serialize(new OneStringField { F1 = "hello" });

        byte[] tamperedPayload = RewriteCompatibleTypeMetaTypeId(payload, (uint)TypeId.Struct);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).Build();
        reader.Register<OneStringField>(200);
        Assert.Throws<TypeMismatchException>(() => reader.Deserialize<OneStringField>(tamperedPayload));
    }

    [Fact]
    public void CompatibleTypeMetaCacheMissValidatesBodyHashBeforeCaching()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).Build();
        writer.Register<OneStringField>(200);
        byte[] payload = writer.Serialize(new OneStringField { F1 = "hello" });
        byte[] tamperedPayload = CorruptCompatibleTypeMetaBody(payload);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).Build();
        reader.Register<OneStringField>(200);
        InvalidDataException exception =
            Assert.Throws<InvalidDataException>(() => reader.Deserialize<OneStringField>(tamperedPayload));
        Assert.Contains("TypeMeta metadata hash mismatch", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void TypeMetaHeaderHashIncludesLowHeaderBits()
    {
        TypeMeta typeMeta = new(
            (uint)TypeId.CompatibleStruct,
            201,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            [new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.String, true))]);
        byte[] encoded = typeMeta.Encode();
        ulong header = BinaryPrimitives.ReadUInt64LittleEndian(encoded);
        int bodyOffset = TypeMetaBodyOffset(encoded, header);
        ulong hashMask = ulong.MaxValue << 12;
        ulong bodyOnlyHash = BodyOnlyTypeMetaHashBits(encoded.AsSpan(bodyOffset));
        Assert.NotEqual(header & hashMask, bodyOnlyHash);

        byte[] malformed = (byte[])encoded.Clone();
        BinaryPrimitives.WriteUInt64LittleEndian(
            malformed,
            bodyOnlyHash | (header & ~hashMask));

        InvalidDataException exception =
            Assert.Throws<InvalidDataException>(() => TypeMeta.Decode(malformed));
        Assert.Contains("TypeMeta metadata hash mismatch", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void TypeMetaAssignFieldIdsPrefersIdAndFallsBackToName()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(null, "int_value", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
            new TypeMetaFieldInfo(2, "name", new TypeMetaFieldType((uint)TypeId.String, true)),
        ];
        List<TypeMetaFieldInfo> remoteFields =
        [
            new TypeMetaFieldInfo(2, "$tag2", new TypeMetaFieldType((uint)TypeId.String, true)),
            new TypeMetaFieldInfo(null, "intValue", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
            new TypeMetaFieldInfo(99, "$tag99", new TypeMetaFieldType((uint)TypeId.String, true)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            500,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            remoteFields);

        TypeMeta.AssignFieldIds(remoteTypeMeta, localFields);
        Assert.Equal(2, remoteTypeMeta.Fields[0].AssignedFieldId);
        Assert.Equal(0, remoteTypeMeta.Fields[1].AssignedFieldId);
        Assert.Equal(-1, remoteTypeMeta.Fields[2].AssignedFieldId);
    }

    [Fact]
    public void TypeMetaAssignFieldIdsDoesNotNameMatchTaggedLocalField()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.String, false)),
        ];
        List<TypeMetaFieldInfo> remoteFields =
        [
            new TypeMetaFieldInfo(null, "value", new TypeMetaFieldType((uint)TypeId.String, false)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            505,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            remoteFields);

        TypeMeta.AssignFieldIds(remoteTypeMeta, localFields);
        Assert.Equal(-1, remoteTypeMeta.Fields[0].AssignedFieldId);
    }

    [Fact]
    public void TypeMetaAssignFieldIdsThrowsOnDuplicateRemoteNameBinding()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(null, "value", new TypeMetaFieldType((uint)TypeId.String, false)),
        ];
        List<TypeMetaFieldInfo> remoteFields =
        [
            new TypeMetaFieldInfo(null, "value", new TypeMetaFieldType((uint)TypeId.String, false)),
            new TypeMetaFieldInfo(null, "value", new TypeMetaFieldType((uint)TypeId.String, false)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            506,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            remoteFields);

        InvalidDataException exception = Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteTypeMeta, localFields));
        Assert.Contains("duplicates local field value", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void RejectsTypeMismatchedField()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
        ];
        List<TypeMetaFieldInfo> remoteFields =
        [
            new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Binary, false)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            501,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            remoteFields);

        InvalidDataException exception =
            Assert.Throws<InvalidDataException>(() => TypeMeta.AssignFieldIds(remoteTypeMeta, localFields));
        Assert.Contains("remote and local field schemas are not compatible", exception.Message);
    }

    [Fact]
    public void TypeMetaAssignScalarConversion()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(1, "value", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
        ];
        List<TypeMetaFieldInfo> remoteFields =
        [
            new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.String, false)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            501,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            remoteFields);

        TypeMeta.AssignFieldIds(remoteTypeMeta, localFields);
        Assert.Equal(1, remoteTypeMeta.Fields[0].AssignedFieldId);
    }

    [Fact]
    public void TypeMetaAssignFieldIdsNormalizesStructLikeTypeIds()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(1, "payload", new TypeMetaFieldType((uint)TypeId.Struct, true)),
        ];
        List<TypeMetaFieldInfo> remoteFields =
        [
            new TypeMetaFieldInfo(1, "$tag1", new TypeMetaFieldType((uint)TypeId.Unknown, true)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            502,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            remoteFields);

        TypeMeta.AssignFieldIds(remoteTypeMeta, localFields);
        Assert.Equal(0, remoteTypeMeta.Fields[0].AssignedFieldId);
    }

    [Fact]
    public void TypeMetaAssignFieldIdsThrowsOnDuplicateLocalFieldId()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(7, "first", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
            new TypeMetaFieldInfo(7, "second", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
        ];
        List<TypeMetaFieldInfo> remoteFields =
        [
            new TypeMetaFieldInfo(7, "$tag7", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            503,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            remoteFields);

        InvalidDataException exception = Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteTypeMeta, localFields));
        Assert.Contains("duplicate local field id 7", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void TypeMetaAssignFieldIdsThrowsOnDuplicateRemoteFieldId()
    {
        List<TypeMetaFieldInfo> localFields =
        [
            new TypeMetaFieldInfo(9, "value", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
        ];
        List<TypeMetaFieldInfo> remoteFields =
        [
            new TypeMetaFieldInfo(9, "$tag9a", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
            new TypeMetaFieldInfo(9, "$tag9b", new TypeMetaFieldType((uint)TypeId.VarInt32, false)),
        ];
        TypeMeta remoteTypeMeta = new(
            (uint)TypeId.CompatibleStruct,
            504,
            MetaString.Empty('.', '_'),
            MetaString.Empty('$', '_'),
            registerByName: false,
            remoteFields);

        InvalidDataException exception = Assert.Throws<InvalidDataException>(
            () => TypeMeta.AssignFieldIds(remoteTypeMeta, localFields));
        Assert.Contains("duplicate remote field id 9", exception.Message, StringComparison.Ordinal);
    }

    private static byte[] RewriteCompatibleTypeMetaTypeId(byte[] payload, uint embeddedTypeId)
    {
        (int typeMetaStart, int typeMetaEnd, TypeMeta originalTypeMeta) = ReadCompatibleTypeMetaRange(payload);

        TypeMeta rewrittenTypeMeta = new(
            embeddedTypeId,
            originalTypeMeta.UserTypeId,
            originalTypeMeta.NamespaceName,
            originalTypeMeta.TypeName,
            originalTypeMeta.RegisterByName,
            originalTypeMeta.Fields,
            originalTypeMeta.Compressed);
        byte[] rewrittenTypeMetaBytes = rewrittenTypeMeta.Encode();

        byte[] rewrittenPayload = new byte[typeMetaStart + rewrittenTypeMetaBytes.Length + (payload.Length - typeMetaEnd)];
        Buffer.BlockCopy(payload, 0, rewrittenPayload, 0, typeMetaStart);
        Buffer.BlockCopy(rewrittenTypeMetaBytes, 0, rewrittenPayload, typeMetaStart, rewrittenTypeMetaBytes.Length);
        Buffer.BlockCopy(payload, typeMetaEnd, rewrittenPayload, typeMetaStart + rewrittenTypeMetaBytes.Length, payload.Length - typeMetaEnd);
        return rewrittenPayload;
    }

    private static TReader CompatibleRead<TWriter, TReader>(TWriter value, bool trackRef = false)
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(true).TrackRef(trackRef).Build();
        writer.Register<TWriter>(812);
        ForyRuntime reader = ForyRuntime.Builder().Compatible(true).TrackRef(trackRef).Build();
        reader.Register<TReader>(812);
        return reader.Deserialize<TReader>(writer.Serialize(value));
    }

    private static byte[] CorruptCompatibleTypeMetaBody(byte[] payload)
    {
        (int typeMetaStart, int typeMetaEnd, _) = ReadCompatibleTypeMetaRange(payload);
        Assert.True(typeMetaEnd > typeMetaStart + sizeof(ulong));
        byte[] malformed = (byte[])payload.Clone();
        malformed[typeMetaEnd - 1] ^= 1;
        return malformed;
    }

    private static int TypeMetaBodyOffset(byte[] encoded, ulong header)
    {
        ByteReader reader = new(encoded);
        _ = reader.ReadUInt64();
        if ((header & 0xff) == 0xff)
        {
            _ = reader.ReadVarUInt32();
        }

        return reader.Cursor;
    }

    private static ulong BodyOnlyTypeMetaHashBits(ReadOnlySpan<byte> body)
    {
        (ulong bodyHash, _) = MurmurHash3.X64_128(body, 47);
        ulong shifted = bodyHash << 12;
        long signed = unchecked((long)shifted);
        long absSigned = signed == long.MinValue ? signed : Math.Abs(signed);
        return unchecked((ulong)absSigned) & (ulong.MaxValue << 12);
    }

    private static (int TypeMetaStart, int TypeMetaEnd, TypeMeta TypeMeta) ReadCompatibleTypeMetaRange(byte[] payload)
    {
        ByteReader reader = new(payload);
        _ = reader.ReadUInt8(); // frame header bitmap

        sbyte refFlag = reader.ReadInt8();
        Assert.Equal((sbyte)RefFlag.NotNullValue, refFlag);

        uint wireTypeId = reader.ReadUInt8();
        Assert.Equal((uint)TypeId.CompatibleStruct, wireTypeId);

        uint typeMetaIndexMarker = reader.ReadVarUInt32();
        Assert.Equal(0u, typeMetaIndexMarker & 1u);

        int typeMetaStart = reader.Cursor;
        TypeMeta originalTypeMeta = TypeMeta.Decode(reader);
        int typeMetaEnd = reader.Cursor;
        return (typeMetaStart, typeMetaEnd, originalTypeMeta);
    }

    private static (ulong Encoding, string Decoded) WriteAndReadString(string value)
    {
        ByteWriter writer = new();
        TypeResolver resolver = new();
        WriteContext writeContext = new(writer, resolver, trackRef: false, compatible: false);
        StringSerializer.WriteString(writeContext, value);

        byte[] payload = writer.ToArray();
        ByteReader headerReader = new(payload);
        ulong header = headerReader.ReadVarUInt36Small();
        ulong encoding = header & 0x03;
        int byteLength = checked((int)(header >> 2));
        Assert.Equal(payload.Length - headerReader.Cursor, byteLength);

        Config config = ForyRuntime.Builder().Compatible(false).Build().Config;
        ReadContext readContext = new(new ByteReader(payload), resolver, config);
        string decoded = StringSerializer.ReadString(readContext);
        Assert.Equal(0, readContext.Reader.Remaining);
        return (encoding, decoded);
    }

    private static void AssertUnsignedEqual(UnsignedFields expected, UnsignedFields actual)
    {
        Assert.Equal(expected.U8, actual.U8);
        Assert.Equal(expected.U16, actual.U16);
        Assert.Equal(expected.U32, actual.U32);
        Assert.Equal(expected.U64, actual.U64);
        Assert.Equal(expected.U8Nullable, actual.U8Nullable);
        Assert.Equal(expected.U16Nullable, actual.U16Nullable);
        Assert.Equal(expected.U32Nullable, actual.U32Nullable);
        Assert.Equal(expected.U64Nullable, actual.U64Nullable);
    }

    private static byte[] LateHolderTypeMetaBytes(bool registerExtFirst)
    {
        TypeResolver resolver = new();
        if (registerExtFirst)
        {
            RegisterLateTypeMetaExt(resolver);
        }

        resolver.Register(typeof(LateTypeMetaHolder), "example", "LateTypeMetaHolder");
        if (!registerExtFirst)
        {
            RegisterLateTypeMetaExt(resolver);
        }

        return resolver.GetTypeInfo(typeof(LateTypeMetaHolder))
            .GetTypeMetaCacheEntry(trackRef: false)
            .EncodedBytes;
    }

    private static void RegisterLateTypeMetaExt(TypeResolver resolver)
    {
        TypeInfo typeInfo = TypeInfo.Create(typeof(LateTypeMetaExt), new LateTypeMetaExtSerializer());
        resolver.Register(typeof(LateTypeMetaExt), "example", "LateTypeMetaExt", typeInfo);
    }
}
