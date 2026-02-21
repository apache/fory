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
using System.Text;
using Apache.Fory;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.XlangPeer;

internal static class Program
{
    private const string DataFileEnv = "DATA_FILE";

    private static readonly string[] StringSamples =
    [
        "ab",
        "Rust123",
        "Çüéâäàåçêëèïî",
        "こんにちは",
        "Привет",
        "𝄞🎵🎶",
        "Hello, 世界",
    ];

    private static readonly int[] VarInt32Values =
    [
        int.MinValue,
        int.MinValue + 1,
        -1_000_000,
        -1000,
        -128,
        -1,
        0,
        1,
        127,
        128,
        16_383,
        16_384,
        2_097_151,
        2_097_152,
        268_435_455,
        268_435_456,
        int.MaxValue - 1,
        int.MaxValue,
    ];

    private static readonly uint[] VarUInt32Values =
    [
        0,
        1,
        127,
        128,
        16_383,
        16_384,
        2_097_151,
        2_097_152,
        268_435_455,
        268_435_456,
        2_147_483_646,
        2_147_483_647,
    ];

    private static readonly ulong[] VarUInt64Values =
    [
        0UL,
        1UL,
        127UL,
        128UL,
        16_383UL,
        16_384UL,
        2_097_151UL,
        2_097_152UL,
        268_435_455UL,
        268_435_456UL,
        34_359_738_367UL,
        34_359_738_368UL,
        4_398_046_511_103UL,
        4_398_046_511_104UL,
        562_949_953_421_311UL,
        562_949_953_421_312UL,
        72_057_594_037_927_935UL,
        72_057_594_037_927_936UL,
        long.MaxValue,
    ];

    private static readonly long[] VarInt64Values =
    [
        long.MinValue,
        long.MinValue + 1,
        -1_000_000_000_000L,
        -1_000_000L,
        -1000L,
        -128L,
        -1L,
        0L,
        1L,
        127L,
        1000L,
        1_000_000L,
        1_000_000_000_000L,
        long.MaxValue - 1,
        long.MaxValue,
    ];

    private static int Main(string[] args)
    {
        try
        {
            string caseName = ParseCaseName(args);
            string dataFile = RequireDataFile();
            byte[] input = File.ReadAllBytes(dataFile);
            byte[] output = ExecuteCase(caseName, input);
            File.WriteAllBytes(dataFile, output);
            Console.WriteLine($"case {caseName} passed");
            return 0;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"xlang peer failed: {ex}");
            return 1;
        }
    }

    private static string ParseCaseName(string[] args)
    {
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--case" && i + 1 < args.Length)
            {
                return args[i + 1];
            }
        }

        if (args.Length == 1)
        {
            return args[0];
        }

        throw new InvalidOperationException("Usage: Fory.XlangPeer --case <case_name>");
    }

    private static string RequireDataFile()
    {
        string? dataFile = Environment.GetEnvironmentVariable(DataFileEnv);
        if (string.IsNullOrWhiteSpace(dataFile))
        {
            throw new InvalidOperationException($"{DataFileEnv} environment variable is required");
        }

        return dataFile;
    }

    private static byte[] ExecuteCase(string caseName, byte[] input)
    {
        return caseName switch
        {
            "test_buffer" => CaseBuffer(input),
            "test_buffer_var" => CaseBufferVar(input),
            "test_murmurhash3" => CaseMurmurHash3(input),
            "test_string_serializer" => CaseStringSerializer(input),
            "test_cross_language_serializer" => CaseCrossLanguageSerializer(input),
            "test_simple_struct" => CaseSimpleStruct(input),
            "test_named_simple_struct" => CaseNamedSimpleStruct(input),
            "test_list" => CaseList(input),
            "test_map" => CaseMap(input),
            "test_integer" => CaseInteger(input),
            "test_item" => CaseItem(input),
            "test_color" => CaseColor(input),
            "test_union_xlang" => CaseUnionXlang(input),
            "test_struct_with_list" => CaseStructWithList(input),
            "test_struct_with_map" => CaseStructWithMap(input),
            "test_skip_id_custom" => CaseSkipIdCustom(input),
            "test_skip_name_custom" => CaseSkipNameCustom(input),
            "test_consistent_named" => CaseConsistentNamed(input),
            "test_struct_version_check" => CaseStructVersionCheck(input),
            "test_polymorphic_list" => CasePolymorphicList(input),
            "test_polymorphic_map" => CasePolymorphicMap(input),
            "test_one_field_struct_compatible" => CaseOneFieldStructCompatible(input),
            "test_one_field_struct_schema" => CaseOneFieldStructSchema(input),
            "test_one_string_field_schema" => CaseOneStringFieldSchema(input),
            "test_one_string_field_compatible" => CaseOneStringFieldCompatible(input),
            "test_two_string_field_compatible" => CaseTwoStringFieldCompatible(input),
            "test_schema_evolution_compatible" => CaseSchemaEvolutionCompatible(input),
            "test_schema_evolution_compatible_reverse" => CaseSchemaEvolutionCompatibleReverse(input),
            "test_one_enum_field_schema" => CaseOneEnumFieldSchema(input),
            "test_one_enum_field_compatible" => CaseOneEnumFieldCompatible(input),
            "test_two_enum_field_compatible" => CaseTwoEnumFieldCompatible(input),
            "test_enum_schema_evolution_compatible" => CaseEnumSchemaEvolutionCompatible(input),
            "test_enum_schema_evolution_compatible_reverse" => CaseEnumSchemaEvolutionCompatibleReverse(input),
            "test_nullable_field_schema_consistent_not_null" => CaseNullableFieldSchemaConsistentNotNull(input),
            "test_nullable_field_schema_consistent_null" => CaseNullableFieldSchemaConsistentNull(input),
            "test_nullable_field_compatible_not_null" => CaseNullableFieldCompatibleNotNull(input),
            "test_nullable_field_compatible_null" => CaseNullableFieldCompatibleNull(input),
            "test_ref_schema_consistent" => CaseRefSchemaConsistent(input),
            "test_ref_compatible" => CaseRefCompatible(input),
            "test_collection_element_ref_override" => CaseCollectionElementRefOverride(input),
            "test_circular_ref_schema_consistent" => CaseCircularRefSchemaConsistent(input),
            "test_circular_ref_compatible" => CaseCircularRefCompatible(input),
            "test_unsigned_schema_consistent_simple" => CaseUnsignedSchemaConsistentSimple(input),
            "test_unsigned_schema_consistent" => CaseUnsignedSchemaConsistent(input),
            "test_unsigned_schema_compatible" => CaseUnsignedSchemaCompatible(input),
            _ => throw new InvalidOperationException($"unknown test case {caseName}"),
        };
    }

    private static byte[] CaseBuffer(byte[] input)
    {
        ByteReader reader = new(input);
        Ensure(reader.ReadUInt8() == 1, "bool mismatch");
        Ensure(reader.ReadInt8() == sbyte.MaxValue, "byte mismatch");
        Ensure(reader.ReadInt16() == short.MaxValue, "int16 mismatch");
        Ensure(reader.ReadInt32() == int.MaxValue, "int32 mismatch");
        Ensure(reader.ReadInt64() == long.MaxValue, "int64 mismatch");
        Ensure(Math.Abs(reader.ReadFloat32() - (-1.1f)) < 0.0001f, "float32 mismatch");
        Ensure(Math.Abs(reader.ReadFloat64() - (-1.1d)) < 0.000001d, "float64 mismatch");
        Ensure(reader.ReadVarUInt32() == 100, "varuint32 mismatch");
        int size = reader.ReadInt32();
        byte[] payload = reader.ReadBytes(size);
        Ensure(payload.SequenceEqual("ab"u8.ToArray()), "binary mismatch");
        Ensure(reader.Remaining == 0, "buffer should be fully consumed");

        ByteWriter writer = new();
        writer.WriteUInt8(1);
        writer.WriteInt8(sbyte.MaxValue);
        writer.WriteInt16(short.MaxValue);
        writer.WriteInt32(int.MaxValue);
        writer.WriteInt64(long.MaxValue);
        writer.WriteFloat32(-1.1f);
        writer.WriteFloat64(-1.1d);
        writer.WriteVarUInt32(100);
        writer.WriteInt32(2);
        writer.WriteBytes("ab"u8);
        return writer.ToArray();
    }

    private static byte[] CaseBufferVar(byte[] input)
    {
        ByteReader reader = new(input);
        foreach (int expected in VarInt32Values)
        {
            Ensure(reader.ReadVarInt32() == expected, $"varint32 mismatch {expected}");
        }

        foreach (uint expected in VarUInt32Values)
        {
            Ensure(reader.ReadVarUInt32() == expected, $"varuint32 mismatch {expected}");
        }

        foreach (ulong expected in VarUInt64Values)
        {
            Ensure(reader.ReadVarUInt64() == expected, $"varuint64 mismatch {expected}");
        }

        foreach (long expected in VarInt64Values)
        {
            Ensure(reader.ReadVarInt64() == expected, $"varint64 mismatch {expected}");
        }

        Ensure(reader.Remaining == 0, "buffer var should be fully consumed");

        ByteWriter writer = new();
        foreach (int value in VarInt32Values)
        {
            writer.WriteVarInt32(value);
        }

        foreach (uint value in VarUInt32Values)
        {
            writer.WriteVarUInt32(value);
        }

        foreach (ulong value in VarUInt64Values)
        {
            writer.WriteVarUInt64(value);
        }

        foreach (long value in VarInt64Values)
        {
            writer.WriteVarInt64(value);
        }

        return writer.ToArray();
    }

    private static byte[] CaseMurmurHash3(byte[] input)
    {
        if (input.Length == 32)
        {
            (ulong h1a, ulong h1b) = MurmurHash3.X64_128([1, 2, 8], 47);
            (ulong h2a, ulong h2b) = MurmurHash3.X64_128(Encoding.UTF8.GetBytes("01234567890123456789"), 47);
            ByteWriter writer = new();
            writer.WriteInt64(unchecked((long)h1a));
            writer.WriteInt64(unchecked((long)h1b));
            writer.WriteInt64(unchecked((long)h2a));
            writer.WriteInt64(unchecked((long)h2b));
            return writer.ToArray();
        }

        if (input.Length == 16)
        {
            ByteReader reader = new(input);
            long h1 = reader.ReadInt64();
            long h2 = reader.ReadInt64();
            (ulong expected1, ulong expected2) = MurmurHash3.X64_128([1, 2, 8], 47);
            Ensure(h1 == unchecked((long)expected1), "murmur hash h1 mismatch");
            Ensure(h2 == unchecked((long)expected2), "murmur hash h2 mismatch");
            return [];
        }

        throw new InvalidOperationException($"unexpected murmur hash input length {input.Length}");
    }

    private static byte[] CaseStringSerializer(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        ReadOnlySequence<byte> sequence = new(input);
        foreach (string expected in StringSamples)
        {
            string value = fory.Deserialize<string>(ref sequence);
            Ensure(value == expected, "string value mismatch");
        }

        EnsureConsumed(sequence, nameof(CaseStringSerializer));
        List<byte> output = [];
        foreach (string sample in StringSamples)
        {
            Append(output, fory.SerializeObject(sample));
        }

        return output.ToArray();
    }

    private static byte[] CaseCrossLanguageSerializer(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Color>(101);
        ReadOnlySequence<byte> sequence = new(input);

        bool b1 = fory.Deserialize<bool>(ref sequence);
        bool b2 = fory.Deserialize<bool>(ref sequence);
        int i32 = fory.Deserialize<int>(ref sequence);
        sbyte i8a = fory.Deserialize<sbyte>(ref sequence);
        sbyte i8b = fory.Deserialize<sbyte>(ref sequence);
        short i16a = fory.Deserialize<short>(ref sequence);
        short i16b = fory.Deserialize<short>(ref sequence);
        int i32a = fory.Deserialize<int>(ref sequence);
        int i32b = fory.Deserialize<int>(ref sequence);
        long i64a = fory.Deserialize<long>(ref sequence);
        long i64b = fory.Deserialize<long>(ref sequence);
        float f32 = fory.Deserialize<float>(ref sequence);
        double f64 = fory.Deserialize<double>(ref sequence);
        string str = fory.Deserialize<string>(ref sequence);
        DateOnly day = fory.Deserialize<DateOnly>(ref sequence);
        DateTimeOffset timestamp = fory.Deserialize<DateTimeOffset>(ref sequence);
        bool[] bools = fory.Deserialize<bool[]>(ref sequence);
        byte[] bytes = fory.Deserialize<byte[]>(ref sequence);
        short[] int16s = fory.Deserialize<short[]>(ref sequence);
        int[] int32s = fory.Deserialize<int[]>(ref sequence);
        long[] int64s = fory.Deserialize<long[]>(ref sequence);
        float[] floats = fory.Deserialize<float[]>(ref sequence);
        double[] doubles = fory.Deserialize<double[]>(ref sequence);
        List<string> list = fory.Deserialize<List<string>>(ref sequence);
        HashSet<string> set = fory.Deserialize<HashSet<string>>(ref sequence);
        Dictionary<string, string> map = fory.Deserialize<Dictionary<string, string>>(ref sequence);
        Color color = fory.Deserialize<Color>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseCrossLanguageSerializer));

        Ensure(b1, "bool1 mismatch");
        Ensure(!b2, "bool2 mismatch");
        Ensure(i32 == -1, "int mismatch");
        Ensure(str == "str", "string mismatch");
        Ensure(day == new DateOnly(2021, 11, 23), "date mismatch");
        Ensure(timestamp.ToUnixTimeSeconds() == 100, "timestamp mismatch");
        Ensure(color == Color.White, "color mismatch");

        List<byte> output = [];
        Append(output, fory.SerializeObject(b1));
        Append(output, fory.SerializeObject(b2));
        Append(output, fory.SerializeObject(i32));
        Append(output, fory.SerializeObject(i8a));
        Append(output, fory.SerializeObject(i8b));
        Append(output, fory.SerializeObject(i16a));
        Append(output, fory.SerializeObject(i16b));
        Append(output, fory.SerializeObject(i32a));
        Append(output, fory.SerializeObject(i32b));
        Append(output, fory.SerializeObject(i64a));
        Append(output, fory.SerializeObject(i64b));
        Append(output, fory.SerializeObject(f32));
        Append(output, fory.SerializeObject(f64));
        Append(output, fory.SerializeObject(str));
        Append(output, fory.SerializeObject(day));
        Append(output, fory.SerializeObject(timestamp));
        Append(output, fory.SerializeObject(bools));
        Append(output, fory.SerializeObject(bytes));
        Append(output, fory.SerializeObject(int16s));
        Append(output, fory.SerializeObject(int32s));
        Append(output, fory.SerializeObject(int64s));
        Append(output, fory.SerializeObject(floats));
        Append(output, fory.SerializeObject(doubles));
        Append(output, fory.SerializeObject(list));
        Append(output, fory.SerializeObject(set));
        Append(output, fory.SerializeObject(map));
        Append(output, fory.SerializeObject(color));
        return output.ToArray();
    }

    private static byte[] CaseSimpleStruct(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        RegisterSimpleById(fory);
        return RoundTripSingle<SimpleStruct>(input, fory);
    }

    private static byte[] CaseNamedSimpleStruct(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        RegisterSimpleByName(fory);
        return RoundTripSingle<SimpleStruct>(input, fory);
    }

    private static byte[] CaseList(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Item>(102);
        ReadOnlySequence<byte> sequence = new(input);
        List<string?> strList = fory.Deserialize<List<string?>>(ref sequence);
        List<string?> strList2 = fory.Deserialize<List<string?>>(ref sequence);
        List<Item?> itemList = fory.Deserialize<List<Item?>>(ref sequence);
        List<Item?> itemList2 = fory.Deserialize<List<Item?>>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseList));

        List<byte> output = [];
        Append(output, fory.SerializeObject(strList));
        Append(output, fory.SerializeObject(strList2));
        Append(output, fory.SerializeObject(itemList));
        Append(output, fory.SerializeObject(itemList2));
        return output.ToArray();
    }

    private static byte[] CaseMap(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Item>(102);
        ReadOnlySequence<byte> sequence = new(input);
        NullableKeyDictionary<string, string?> strMap = fory.Deserialize<NullableKeyDictionary<string, string?>>(ref sequence);
        NullableKeyDictionary<string, Item?> itemMap = fory.Deserialize<NullableKeyDictionary<string, Item?>>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseMap));

        List<byte> output = [];
        Append(output, fory.SerializeObject(strMap));
        Append(output, fory.SerializeObject(itemMap));
        return output.ToArray();
    }

    private static byte[] CaseInteger(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Item1>(101);
        ReadOnlySequence<byte> sequence = new(input);
        Item1 obj = fory.Deserialize<Item1>(ref sequence);
        int f1 = fory.Deserialize<int>(ref sequence);
        int f2 = fory.Deserialize<int>(ref sequence);
        int f3 = fory.Deserialize<int>(ref sequence);
        int f4 = fory.Deserialize<int>(ref sequence);
        int f5 = fory.Deserialize<int>(ref sequence);
        int f6 = fory.Deserialize<int>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseInteger));

        Ensure(obj.F1 == 1 && obj.F2 == 2, "item1 primitive fields mismatch");
        Ensure(obj.F3 == 3 && obj.F4 == 4 && obj.F5 == 0 && obj.F6 == 0, "item1 boxed fields mismatch");

        List<byte> output = [];
        Append(output, fory.SerializeObject(obj));
        Append(output, fory.SerializeObject(f1));
        Append(output, fory.SerializeObject(f2));
        Append(output, fory.SerializeObject(f3));
        Append(output, fory.SerializeObject(f4));
        Append(output, fory.SerializeObject(f5));
        Append(output, fory.SerializeObject(f6));
        return output.ToArray();
    }

    private static byte[] CaseItem(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Item>(102);
        ReadOnlySequence<byte> sequence = new(input);
        Item i1 = fory.Deserialize<Item>(ref sequence);
        Item i2 = fory.Deserialize<Item>(ref sequence);
        Item i3 = fory.Deserialize<Item>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseItem));

        List<byte> output = [];
        Append(output, fory.SerializeObject(i1));
        Append(output, fory.SerializeObject(i2));
        Append(output, fory.SerializeObject(i3));
        return output.ToArray();
    }

    private static byte[] CaseColor(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Color>(101);
        ReadOnlySequence<byte> sequence = new(input);
        Color c1 = fory.Deserialize<Color>(ref sequence);
        Color c2 = fory.Deserialize<Color>(ref sequence);
        Color c3 = fory.Deserialize<Color>(ref sequence);
        Color c4 = fory.Deserialize<Color>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseColor));

        List<byte> output = [];
        Append(output, fory.SerializeObject(c1));
        Append(output, fory.SerializeObject(c2));
        Append(output, fory.SerializeObject(c3));
        Append(output, fory.SerializeObject(c4));
        return output.ToArray();
    }

    private static byte[] CaseStructWithList(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<StructWithList>(201);
        ReadOnlySequence<byte> sequence = new(input);
        StructWithList s1 = fory.Deserialize<StructWithList>(ref sequence);
        StructWithList s2 = fory.Deserialize<StructWithList>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseStructWithList));

        List<byte> output = [];
        Append(output, fory.SerializeObject(s1));
        Append(output, fory.SerializeObject(s2));
        return output.ToArray();
    }

    private static byte[] CaseStructWithMap(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<StructWithMap>(202);
        ReadOnlySequence<byte> sequence = new(input);
        StructWithMap s1 = fory.Deserialize<StructWithMap>(ref sequence);
        StructWithMap s2 = fory.Deserialize<StructWithMap>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseStructWithMap));

        List<byte> output = [];
        Append(output, fory.SerializeObject(s1));
        Append(output, fory.SerializeObject(s2));
        return output.ToArray();
    }

    private static byte[] CaseUnionXlang(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<StructWithUnion2>(301);

        ReadOnlySequence<byte> sequence = new(input);
        StructWithUnion2 first = fory.Deserialize<StructWithUnion2>(ref sequence);
        StructWithUnion2 second = fory.Deserialize<StructWithUnion2>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseUnionXlang));

        Ensure(first.Union.Index == 0, "union case index mismatch for first value");
        Ensure(first.Union.Value is string firstValue && firstValue == "hello", "union case value mismatch for first value");
        Ensure(second.Union.Index == 1, "union case index mismatch for second value");
        Ensure(second.Union.Value is long secondValue && secondValue == 42L, "union case value mismatch for second value");

        List<byte> output = [];
        Append(output, fory.SerializeObject(first));
        Append(output, fory.SerializeObject(second));
        return output.ToArray();
    }

    private static byte[] CaseSkipIdCustom(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Color>(101);
        fory.Register<MyStruct>(102);
        fory.Register<MyExt, MyExtSerializer>(103);
        fory.Register<MyWrapper>(104);
        return RoundTripSingle<MyWrapper>(input, fory);
    }

    private static byte[] CaseSkipNameCustom(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Color>("color");
        fory.Register<MyStruct>("my_struct");
        fory.Register<MyExt, MyExtSerializer>(string.Empty, "my_ext");
        fory.Register<MyWrapper>("my_wrapper");
        return RoundTripSingle<MyWrapper>(input, fory);
    }

    private static byte[] CaseConsistentNamed(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false, checkStructVersion: true);
        fory.Register<Color>("color");
        fory.Register<MyStruct>("my_struct");
        fory.Register<MyExt, MyExtSerializer>(string.Empty, "my_ext");

        ReadOnlySequence<byte> sequence = new(input);
        List<byte> output = [];
        for (int i = 0; i < 3; i++)
        {
            Color color = fory.Deserialize<Color>(ref sequence);
            Append(output, fory.SerializeObject(color));
        }

        for (int i = 0; i < 3; i++)
        {
            MyStruct myStruct = fory.Deserialize<MyStruct>(ref sequence);
            Append(output, fory.SerializeObject(myStruct));
        }

        for (int i = 0; i < 3; i++)
        {
            MyExt myExt = fory.Deserialize<MyExt>(ref sequence);
            Append(output, fory.SerializeObject(myExt));
        }

        EnsureConsumed(sequence, nameof(CaseConsistentNamed));
        return output.ToArray();
    }

    private static byte[] CaseStructVersionCheck(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false, checkStructVersion: true);
        fory.Register<VersionCheckStruct>(201);
        return RoundTripSingle<VersionCheckStruct>(input, fory);
    }

    private static byte[] CasePolymorphicList(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Dog>(302);
        fory.Register<Cat>(303);
        fory.Register<AnimalListHolder>(304);

        ReadOnlySequence<byte> sequence = new(input);
        List<object?> animals = fory.Deserialize<List<object?>>(ref sequence);
        AnimalListHolder holder = fory.Deserialize<AnimalListHolder>(ref sequence);
        EnsureConsumed(sequence, nameof(CasePolymorphicList));

        List<byte> output = [];
        Append(output, fory.SerializeObject(animals));
        Append(output, fory.SerializeObject(holder));
        return output.ToArray();
    }

    private static byte[] CasePolymorphicMap(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<Dog>(302);
        fory.Register<Cat>(303);
        fory.Register<AnimalMapHolder>(305);

        ReadOnlySequence<byte> sequence = new(input);
        Dictionary<string, object?> map = fory.Deserialize<Dictionary<string, object?>>(ref sequence);
        AnimalMapHolder holder = fory.Deserialize<AnimalMapHolder>(ref sequence);
        EnsureConsumed(sequence, nameof(CasePolymorphicMap));

        List<byte> output = [];
        Append(output, fory.SerializeObject(map));
        Append(output, fory.SerializeObject(holder));
        return output.ToArray();
    }

    private static byte[] CaseOneFieldStructCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<OneFieldStruct>(200);
        return RoundTripSingle<OneFieldStruct>(input, fory);
    }

    private static byte[] CaseOneFieldStructSchema(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false);
        fory.Register<OneFieldStruct>(200);
        return RoundTripSingle<OneFieldStruct>(input, fory);
    }

    private static byte[] CaseOneStringFieldSchema(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false);
        fory.Register<OneStringFieldStruct>(200);
        return RoundTripSingle<OneStringFieldStruct>(input, fory);
    }

    private static byte[] CaseOneStringFieldCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<OneStringFieldStruct>(200);
        return RoundTripSingle<OneStringFieldStruct>(input, fory);
    }

    private static byte[] CaseTwoStringFieldCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<TwoStringFieldStruct>(201);
        return RoundTripSingle<TwoStringFieldStruct>(input, fory);
    }

    private static byte[] CaseSchemaEvolutionCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<TwoStringFieldStruct>(200);
        return RoundTripSingle<TwoStringFieldStruct>(input, fory);
    }

    private static byte[] CaseSchemaEvolutionCompatibleReverse(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<OneStringFieldStruct>(200);
        return RoundTripSingle<OneStringFieldStruct>(input, fory);
    }

    private static byte[] CaseOneEnumFieldSchema(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false);
        fory.Register<TestEnum>(210);
        fory.Register<OneEnumFieldStruct>(211);
        return RoundTripSingle<OneEnumFieldStruct>(input, fory);
    }

    private static byte[] CaseOneEnumFieldCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<TestEnum>(210);
        fory.Register<OneEnumFieldStruct>(211);
        return RoundTripSingle<OneEnumFieldStruct>(input, fory);
    }

    private static byte[] CaseTwoEnumFieldCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<TestEnum>(210);
        fory.Register<TwoEnumFieldStruct>(212);
        return RoundTripSingle<TwoEnumFieldStruct>(input, fory);
    }

    private static byte[] CaseEnumSchemaEvolutionCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<TestEnum>(210);
        fory.Register<TwoEnumFieldStruct>(211);
        return RoundTripSingle<TwoEnumFieldStruct>(input, fory);
    }

    private static byte[] CaseEnumSchemaEvolutionCompatibleReverse(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<TestEnum>(210);
        fory.Register<TwoEnumFieldStruct>(211);

        ReadOnlySequence<byte> sequence = new(input);
        TwoEnumFieldStruct value = fory.Deserialize<TwoEnumFieldStruct>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseEnumSchemaEvolutionCompatibleReverse));
        Ensure(value.F1 == TestEnum.ValueC, "enum schema evolution reverse F1 mismatch");
        Ensure(value.F2 == TestEnum.ValueA, "enum schema evolution reverse F2 default mismatch");
        return fory.SerializeObject(value);
    }

    private static byte[] CaseNullableFieldSchemaConsistentNotNull(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false);
        fory.Register<NullableComprehensiveSchemaConsistent>(401);
        return RoundTripSingle<NullableComprehensiveSchemaConsistent>(input, fory);
    }

    private static byte[] CaseNullableFieldSchemaConsistentNull(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false);
        fory.Register<NullableComprehensiveSchemaConsistent>(401);
        return RoundTripSingle<NullableComprehensiveSchemaConsistent>(input, fory);
    }

    private static byte[] CaseNullableFieldCompatibleNotNull(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<NullableComprehensiveCompatible>(402);
        return RoundTripSingle<NullableComprehensiveCompatible>(input, fory);
    }

    private static byte[] CaseNullableFieldCompatibleNull(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<NullableComprehensiveCompatible>(402);
        return RoundTripSingle<NullableComprehensiveCompatible>(input, fory);
    }

    private static byte[] CaseRefSchemaConsistent(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false, trackRef: true);
        fory.Register<RefInnerSchemaConsistent>(501);
        fory.Register<RefOuterSchemaConsistent>(502);

        ReadOnlySequence<byte> sequence = new(input);
        RefOuterSchemaConsistent outer = fory.Deserialize<RefOuterSchemaConsistent>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseRefSchemaConsistent));
        Ensure(ReferenceEquals(outer.Inner1, outer.Inner2), "reference tracking mismatch");
        return fory.SerializeObject(outer);
    }

    private static byte[] CaseRefCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true, trackRef: true);
        fory.Register<RefInnerCompatible>(503);
        fory.Register<RefOuterCompatible>(504);

        ReadOnlySequence<byte> sequence = new(input);
        RefOuterCompatible outer = fory.Deserialize<RefOuterCompatible>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseRefCompatible));
        Ensure(ReferenceEquals(outer.Inner1, outer.Inner2), "reference tracking mismatch");
        return fory.SerializeObject(outer);
    }

    private static byte[] CaseCollectionElementRefOverride(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false, trackRef: true);
        fory.Register<RefOverrideElement>(701);
        fory.Register<RefOverrideContainer>(702);

        ReadOnlySequence<byte> sequence = new(input);
        RefOverrideContainer container = fory.Deserialize<RefOverrideContainer>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseCollectionElementRefOverride));

        if (container.ListField.Count > 0)
        {
            RefOverrideElement? shared = container.ListField[0];
            if (shared is not null)
            {
                if (container.ListField.Count > 1)
                {
                    container.ListField[1] = shared;
                }

                if (container.MapField.ContainsKey("k1"))
                {
                    container.MapField["k1"] = shared;
                }

                if (container.MapField.ContainsKey("k2"))
                {
                    container.MapField["k2"] = shared;
                }
            }
        }

        return fory.SerializeObject(container);
    }

    private static byte[] CaseCircularRefSchemaConsistent(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false, trackRef: true);
        fory.Register<CircularRefStruct>(601);

        ReadOnlySequence<byte> sequence = new(input);
        CircularRefStruct value = fory.Deserialize<CircularRefStruct>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseCircularRefSchemaConsistent));
        Ensure(ReferenceEquals(value, value.SelfRef), "circular ref mismatch");
        return fory.SerializeObject(value);
    }

    private static byte[] CaseCircularRefCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true, trackRef: true);
        fory.Register<CircularRefStruct>(602);

        ReadOnlySequence<byte> sequence = new(input);
        CircularRefStruct value = fory.Deserialize<CircularRefStruct>(ref sequence);
        EnsureConsumed(sequence, nameof(CaseCircularRefCompatible));
        Ensure(ReferenceEquals(value, value.SelfRef), "circular ref mismatch");
        return fory.SerializeObject(value);
    }

    private static byte[] CaseUnsignedSchemaConsistentSimple(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false);
        fory.Register<UnsignedSchemaConsistentSimple>(1);
        return RoundTripSingle<UnsignedSchemaConsistentSimple>(input, fory);
    }

    private static byte[] CaseUnsignedSchemaConsistent(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: false);
        fory.Register<UnsignedSchemaConsistent>(501);
        return RoundTripSingle<UnsignedSchemaConsistent>(input, fory);
    }

    private static byte[] CaseUnsignedSchemaCompatible(byte[] input)
    {
        ForyRuntime fory = BuildFory(compatible: true);
        fory.Register<UnsignedSchemaCompatible>(502);
        return RoundTripSingle<UnsignedSchemaCompatible>(input, fory);
    }

    private static byte[] RoundTripSingle<T>(byte[] input, ForyRuntime fory)
    {
        ReadOnlySequence<byte> sequence = new(input);
        T value = fory.Deserialize<T>(ref sequence);
        EnsureConsumed(sequence, typeof(T).Name);
        return fory.SerializeObject(value);
    }

    private static void RegisterSimpleById(ForyRuntime fory)
    {
        fory.Register<Color>(101);
        fory.Register<Item>(102);
        fory.Register<SimpleStruct>(103);
    }

    private static void RegisterSimpleByName(ForyRuntime fory)
    {
        fory.Register<Color>("demo", "color");
        fory.Register<Item>("demo", "item");
        fory.Register<SimpleStruct>("demo", "simple_struct");
    }

    private static ForyRuntime BuildFory(bool compatible, bool trackRef = false, bool checkStructVersion = false)
    {
        return ForyRuntime.Builder()
            .Compatible(compatible)
            .TrackRef(trackRef)
            .CheckStructVersion(checkStructVersion)
            .Build();
    }

    private static void Append(List<byte> target, byte[] payload)
    {
        target.AddRange(payload);
    }

    private static void EnsureConsumed(ReadOnlySequence<byte> sequence, string caseName)
    {
        Ensure(sequence.Length == 0, $"case {caseName} did not consume full payload");
    }

    private static void Ensure(bool condition, string message)
    {
        if (!condition)
        {
            throw new InvalidOperationException(message);
        }
    }
}

[ForyObject]
public enum Color
{
    Green,
    Red,
    Blue,
    White,
}

[ForyObject]
public sealed class Item
{
    public string Name { get; set; } = string.Empty;
}

[ForyObject]
public sealed class SimpleStruct
{
    public Dictionary<int, double> F1 { get; set; } = [];
    public int F2 { get; set; }
    public Item F3 { get; set; } = new();
    public string F4 { get; set; } = string.Empty;
    public Color F5 { get; set; }
    public List<string> F6 { get; set; } = [];
    public int F7 { get; set; }
    public int F8 { get; set; }
    public int Last { get; set; }
}

[ForyObject]
public sealed class Item1
{
    public int F1 { get; set; }
    public int F2 { get; set; }
    public int F3 { get; set; }
    public int F4 { get; set; }
    public int F5 { get; set; }
    public int F6 { get; set; }
}

[ForyObject]
public sealed class StructWithList
{
    public List<string?> Items { get; set; } = [];
}

[ForyObject]
public sealed class StructWithMap
{
    public NullableKeyDictionary<string, string?> Data { get; set; } = new();
}

[ForyObject]
public sealed class StructWithUnion2
{
    public Union2<string, long> Union { get; set; } = Union2<string, long>.OfT1(string.Empty);
}

[ForyObject]
public sealed class MyStruct
{
    public int Id { get; set; }
}

[ForyObject]
public sealed class MyExt
{
    public int Id { get; set; }
}

public readonly struct MyExtSerializer : IStaticSerializer<MyExtSerializer, MyExt>
{
    public static TypeId StaticTypeId => TypeId.Ext;
    public static bool IsNullableType => true;
    public static bool IsReferenceTrackableType => true;
    public static MyExt DefaultValue => null!;
    public static bool IsNone(in MyExt value) => value is null;

    public static void WriteData(ref WriteContext context, in MyExt value, bool hasGenerics)
    {
        _ = hasGenerics;
        context.Writer.WriteVarInt32((value ?? new MyExt()).Id);
    }

    public static MyExt ReadData(ref ReadContext context)
    {
        return new MyExt { Id = context.Reader.ReadVarInt32() };
    }
}

[ForyObject]
public sealed class MyWrapper
{
    public Color Color { get; set; }
    public MyExt MyExt { get; set; } = new();
    public MyStruct MyStruct { get; set; } = new();
}

[ForyObject]
public sealed class EmptyWrapper
{
}

[ForyObject]
public sealed class VersionCheckStruct
{
    public int F1 { get; set; }
    public string? F2 { get; set; }
    public double F3 { get; set; }
}

[ForyObject]
public sealed class Dog
{
    public int Age { get; set; }
    public string? Name { get; set; }
}

[ForyObject]
public sealed class Cat
{
    public int Age { get; set; }
    public int Lives { get; set; }
}

[ForyObject]
public sealed class AnimalListHolder
{
    public List<object?> Animals { get; set; } = [];
}

[ForyObject]
public sealed class AnimalMapHolder
{
    public Dictionary<string, object?> AnimalMap { get; set; } = [];
}

[ForyObject]
public sealed class OneFieldStruct
{
    public int Value { get; set; }
}

[ForyObject]
public sealed class OneStringFieldStruct
{
    public string? F1 { get; set; }
}

[ForyObject]
public sealed class TwoStringFieldStruct
{
    public string F1 { get; set; } = string.Empty;
    public string F2 { get; set; } = string.Empty;
}

[ForyObject]
public enum TestEnum
{
    ValueA,
    ValueB,
    ValueC,
}

[ForyObject]
public sealed class OneEnumFieldStruct
{
    public TestEnum F1 { get; set; }
}

[ForyObject]
public sealed class TwoEnumFieldStruct
{
    public TestEnum F1 { get; set; }
    public TestEnum F2 { get; set; }
}

[ForyObject]
public sealed class NullableComprehensiveSchemaConsistent
{
    public sbyte ByteField { get; set; }
    public short ShortField { get; set; }
    public int IntField { get; set; }
    public long LongField { get; set; }
    public float FloatField { get; set; }
    public double DoubleField { get; set; }
    public bool BoolField { get; set; }

    public string StringField { get; set; } = string.Empty;
    public List<string> ListField { get; set; } = [];
    public HashSet<string> SetField { get; set; } = [];
    public NullableKeyDictionary<string, string> MapField { get; set; } = new();

    public int? NullableInt { get; set; }
    public long? NullableLong { get; set; }
    public float? NullableFloat { get; set; }
    public double? NullableDouble { get; set; }
    public bool? NullableBool { get; set; }
    public string? NullableString { get; set; }
    public List<string>? NullableList { get; set; }
    public HashSet<string>? NullableSet { get; set; }
    public NullableKeyDictionary<string, string>? NullableMap { get; set; }
}

[ForyObject]
public sealed class NullableComprehensiveCompatible
{
    public sbyte ByteField { get; set; }
    public short ShortField { get; set; }
    public int IntField { get; set; }
    public long LongField { get; set; }
    public float FloatField { get; set; }
    public double DoubleField { get; set; }
    public bool BoolField { get; set; }

    public int BoxedInt { get; set; }
    public long BoxedLong { get; set; }
    public float BoxedFloat { get; set; }
    public double BoxedDouble { get; set; }
    public bool BoxedBool { get; set; }

    public string StringField { get; set; } = string.Empty;
    public List<string> ListField { get; set; } = [];
    public HashSet<string> SetField { get; set; } = [];
    public NullableKeyDictionary<string, string> MapField { get; set; } = new();

    public int NullableInt1 { get; set; }
    public long NullableLong1 { get; set; }
    public float NullableFloat1 { get; set; }
    public double NullableDouble1 { get; set; }
    public bool NullableBool1 { get; set; }

    public string NullableString2 { get; set; } = string.Empty;
    public List<string> NullableList2 { get; set; } = [];
    public HashSet<string> NullableSet2 { get; set; } = [];
    public NullableKeyDictionary<string, string> NullableMap2 { get; set; } = new();
}

[ForyObject]
public sealed class RefInnerSchemaConsistent
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
}

[ForyObject]
public sealed class RefOuterSchemaConsistent
{
    public RefInnerSchemaConsistent? Inner1 { get; set; }
    public RefInnerSchemaConsistent? Inner2 { get; set; }
}

[ForyObject]
public sealed class RefInnerCompatible
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
}

[ForyObject]
public sealed class RefOuterCompatible
{
    public RefInnerCompatible? Inner1 { get; set; }
    public RefInnerCompatible? Inner2 { get; set; }
}

[ForyObject]
public sealed class RefOverrideElement
{
    public int Id { get; set; }
    public string Name { get; set; } = string.Empty;
}

[ForyObject]
public sealed class RefOverrideContainer
{
    public List<RefOverrideElement?> ListField { get; set; } = [];
    public Dictionary<string, RefOverrideElement?> MapField { get; set; } = [];
}

[ForyObject]
public sealed class CircularRefStruct
{
    public string Name { get; set; } = string.Empty;
    public CircularRefStruct? SelfRef { get; set; }
}

[ForyObject]
public sealed class UnsignedSchemaConsistentSimple
{
    [Field(Encoding = FieldEncoding.Tagged)]
    public ulong U64Tagged { get; set; }

    [Field(Encoding = FieldEncoding.Tagged)]
    public ulong? U64TaggedNullable { get; set; }
}

[ForyObject]
public sealed class UnsignedSchemaConsistent
{
    public byte U8Field { get; set; }
    public ushort U16Field { get; set; }
    public uint U32VarField { get; set; }

    [Field(Encoding = FieldEncoding.Fixed)]
    public uint U32FixedField { get; set; }

    public ulong U64VarField { get; set; }

    [Field(Encoding = FieldEncoding.Fixed)]
    public ulong U64FixedField { get; set; }

    [Field(Encoding = FieldEncoding.Tagged)]
    public ulong U64TaggedField { get; set; }

    public byte? U8NullableField { get; set; }
    public ushort? U16NullableField { get; set; }
    public uint? U32VarNullableField { get; set; }

    [Field(Encoding = FieldEncoding.Fixed)]
    public uint? U32FixedNullableField { get; set; }

    public ulong? U64VarNullableField { get; set; }

    [Field(Encoding = FieldEncoding.Fixed)]
    public ulong? U64FixedNullableField { get; set; }

    [Field(Encoding = FieldEncoding.Tagged)]
    public ulong? U64TaggedNullableField { get; set; }
}

[ForyObject]
public sealed class UnsignedSchemaCompatible
{
    public byte? U8Field1 { get; set; }
    public ushort? U16Field1 { get; set; }
    public uint? U32VarField1 { get; set; }

    [Field(Encoding = FieldEncoding.Fixed)]
    public uint? U32FixedField1 { get; set; }

    public ulong? U64VarField1 { get; set; }

    [Field(Encoding = FieldEncoding.Fixed)]
    public ulong? U64FixedField1 { get; set; }

    [Field(Encoding = FieldEncoding.Tagged)]
    public ulong? U64TaggedField1 { get; set; }

    public byte U8Field2 { get; set; }
    public ushort U16Field2 { get; set; }
    public uint U32VarField2 { get; set; }

    [Field(Encoding = FieldEncoding.Fixed)]
    public uint U32FixedField2 { get; set; }

    public ulong U64VarField2 { get; set; }

    [Field(Encoding = FieldEncoding.Fixed)]
    public ulong U64FixedField2 { get; set; }

    [Field(Encoding = FieldEncoding.Tagged)]
    public ulong U64TaggedField2 { get; set; }
}
