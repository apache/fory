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

[ForyObject]
public enum TestColor
{
    Green,
    Red,
    Blue,
    White,
}

[ForyObject]
public sealed class Address
{
    public string Street { get; set; } = string.Empty;
    public int Zip { get; set; }
}

[ForyObject]
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

[ForyObject]
public sealed class Node
{
    public int Value { get; set; }
    public Node? Next { get; set; }
}

[ForyObject]
public sealed class FieldOrder
{
    public string Z { get; set; } = string.Empty;
    public long A { get; set; }
    public short B { get; set; }
    public int C { get; set; }
}

[ForyObject]
public sealed class EncodedNumbers
{
    [Field(Encoding = FieldEncoding.Fixed)]
    public uint U32Fixed { get; set; }

    [Field(Encoding = FieldEncoding.Tagged)]
    public ulong U64Tagged { get; set; }
}

[ForyObject]
public sealed class OneStringField
{
    public string? F1 { get; set; }
}

[ForyObject]
public sealed class TwoStringField
{
    public string F1 { get; set; } = string.Empty;
    public string F2 { get; set; } = string.Empty;
}

[ForyObject]
public sealed class StructWithEnum
{
    public string Name { get; set; } = string.Empty;
    public TestColor Color { get; set; }
    public int Value { get; set; }
}

[ForyObject]
public sealed class StructWithNullableMap
{
    public NullableKeyDictionary<string, string?> Data { get; set; } = new();
}

[ForyObject]
public sealed class StructWithUnion2
{
    public Union2<string, long> Union { get; set; } = Union2<string, long>.OfT1(string.Empty);
}

[ForyObject]
public sealed class DynamicAnyHolder
{
    public object? AnyValue { get; set; }
    public HashSet<object> AnySet { get; set; } = [];
    public Dictionary<object, object?> AnyMap { get; set; } = [];
}

public sealed class ForyRuntimeTests
{
    private const ulong StringEncodingLatin1 = 0;
    private const ulong StringEncodingUtf16 = 1;
    private const ulong StringEncodingUtf8 = 2;

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
    public void StreamDeserializeObjectConsumesSingleFrame()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();

        byte[] p1 = fory.SerializeObject("first");
        byte[] p2 = fory.SerializeObject(99);
        byte[] joined = new byte[p1.Length + p2.Length];
        Buffer.BlockCopy(p1, 0, joined, 0, p1.Length);
        Buffer.BlockCopy(p2, 0, joined, p1.Length, p2.Length);

        ReadOnlySequence<byte> sequence = new(joined);
        object? first = fory.DeserializeObject(ref sequence);
        object? second = fory.DeserializeObject(ref sequence);

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
    public void MacroClassReferenceTracking()
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
    public void MacroFieldOrderFollowsForyRules()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<FieldOrder>(300);

        FieldOrder value = new() { Z = "tail", A = 123_456_789, B = 17, C = 99 };
        byte[] data = fory.Serialize(value);

        ByteReader reader = new(data);
        _ = fory.ReadHead(reader);
        _ = reader.ReadInt8();
        _ = reader.ReadVarUInt32();
        _ = reader.ReadVarUInt32();
        _ = reader.ReadInt32();

        short first = reader.ReadInt16();
        long second = reader.ReadVarInt64();
        int third = reader.ReadVarInt32();
        ReadContext tailContext = new(reader, new TypeResolver(), false, false);
        string fourth = tailContext.TypeResolver.GetSerializer<string>().ReadData(ref tailContext);

        Assert.Equal(value.B, first);
        Assert.Equal(value.A, second);
        Assert.Equal(value.C, third);
        Assert.Equal(value.Z, fourth);
    }

    [Fact]
    public void MacroFieldEncodingOverridesForUnsignedTypes()
    {
        ForyRuntime fory = ForyRuntime.Builder().Build();
        fory.Register<EncodedNumbers>(301);

        EncodedNumbers value = new()
        {
            U32Fixed = 0x11223344u,
            U64Tagged = (ulong)int.MaxValue + 99UL,
        };

        EncodedNumbers decoded = fory.Deserialize<EncodedNumbers>(fory.Serialize(value));
        Assert.Equal(value.U32Fixed, decoded.U32Fixed);
        Assert.Equal(value.U64Tagged, decoded.U64Tagged);
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

    [Fact]
    public void SchemaVersionMismatchThrows()
    {
        ForyRuntime writer = ForyRuntime.Builder().Compatible(false).Build();
        writer.Register<OneStringField>(200);

        ForyRuntime reader = ForyRuntime.Builder().Compatible(false).Build();
        reader.Register<TwoStringField>(200);

        byte[] payload = writer.Serialize(new OneStringField { F1 = "hello" });
        Assert.Throws<InvalidDataException>(() => { _ = reader.Deserialize<TwoStringField>(payload); });
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
            Assert.IsType<Dictionary<object, object?>>(fory.DeserializeObject(fory.SerializeObject(map)));
        Assert.Equal(3, mapDecoded.Count);
        Assert.Equal(7, mapDecoded["k1"]);
        Assert.Equal("v2", mapDecoded[2]);
        Assert.True(mapDecoded.ContainsKey(true));
        Assert.Null(mapDecoded[true]);

        HashSet<object> set = ["a", 7, false];
        HashSet<object?> setDecoded =
            Assert.IsType<HashSet<object?>>(fory.DeserializeObject(fory.SerializeObject(set)));
        Assert.Equal(3, setDecoded.Count);
        Assert.Contains("a", setDecoded);
        Assert.Contains(7, setDecoded);
        Assert.Contains(false, setDecoded);
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

    private static (ulong Encoding, string Decoded) WriteAndReadString(string value)
    {
        ByteWriter writer = new();
        TypeResolver resolver = new();
        WriteContext writeContext = new(writer, resolver, trackRef: false, compatible: false);
        StringSerializer.WriteString(ref writeContext, value);

        byte[] payload = writer.ToArray();
        ByteReader headerReader = new(payload);
        ulong header = headerReader.ReadVarUInt36Small();
        ulong encoding = header & 0x03;
        int byteLength = checked((int)(header >> 2));
        Assert.Equal(payload.Length - headerReader.Cursor, byteLength);

        ReadContext readContext = new(new ByteReader(payload), resolver, trackRef: false, compatible: false);
        string decoded = StringSerializer.ReadString(ref readContext);
        Assert.Equal(0, readContext.Reader.Remaining);
        return (encoding, decoded);
    }
}
