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

using System.Globalization;
using Apache.Fory;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.IdlTests;

public sealed class RoundtripTests
{
    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void AddressBookRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, false);
        addressbook.AddressbookForyRegistration.Register(fory);

        addressbook.AddressBook book = BuildAddressBook();
        addressbook.AddressBook decoded = fory.Deserialize<addressbook.AddressBook>(fory.Serialize(book));
        AssertAddressBook(book, decoded);

        RoundTripFile(fory, "DATA_FILE", book, AssertAddressBook);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void AutoIdRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, false);
        auto_id.AutoIdForyRegistration.Register(fory);

        auto_id.Envelope envelope = BuildEnvelope();
        auto_id.Wrapper wrapper = auto_id.Wrapper.Envelope(envelope);

        auto_id.Envelope envelopeDecoded = fory.Deserialize<auto_id.Envelope>(fory.Serialize(envelope));
        AssertEnvelope(envelope, envelopeDecoded);

        auto_id.Wrapper wrapperDecoded = fory.Deserialize<auto_id.Wrapper>(fory.Serialize(wrapper));
        Assert.True(wrapperDecoded.IsEnvelope);
        AssertEnvelope(envelope, wrapperDecoded.EnvelopeValue());

        RoundTripFile(fory, "DATA_FILE_AUTO_ID", envelope, AssertEnvelope);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void PrimitiveTypesRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, false);
        complex_pb.ComplexPbForyRegistration.Register(fory);

        complex_pb.PrimitiveTypes types = BuildPrimitiveTypes();
        complex_pb.PrimitiveTypes decoded = fory.Deserialize<complex_pb.PrimitiveTypes>(fory.Serialize(types));
        AssertPrimitiveTypes(types, decoded);

        RoundTripFile(fory, "DATA_FILE_PRIMITIVES", types, AssertPrimitiveTypes);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void CollectionRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, false);
        collection.CollectionForyRegistration.Register(fory);

        collection.NumericCollections collections = BuildNumericCollections();
        collection.NumericCollectionUnion unionValue = BuildNumericCollectionUnion();
        collection.NumericCollectionsArray collectionsArray = BuildNumericCollectionsArray();
        collection.NumericCollectionArrayUnion arrayUnion = BuildNumericCollectionArrayUnion();

        collection.NumericCollections collectionsDecoded =
            fory.Deserialize<collection.NumericCollections>(fory.Serialize(collections));
        AssertNumericCollections(collections, collectionsDecoded);

        collection.NumericCollectionUnion unionDecoded =
            fory.Deserialize<collection.NumericCollectionUnion>(fory.Serialize(unionValue));
        AssertNumericCollectionUnion(unionValue, unionDecoded);

        collection.NumericCollectionsArray arrayDecoded =
            fory.Deserialize<collection.NumericCollectionsArray>(fory.Serialize(collectionsArray));
        AssertNumericCollectionsArray(collectionsArray, arrayDecoded);

        collection.NumericCollectionArrayUnion arrayUnionDecoded =
            fory.Deserialize<collection.NumericCollectionArrayUnion>(fory.Serialize(arrayUnion));
        AssertNumericCollectionArrayUnion(arrayUnion, arrayUnionDecoded);

        RoundTripFile(fory, "DATA_FILE_COLLECTION", collections, AssertNumericCollections);
        RoundTripFile(fory, "DATA_FILE_COLLECTION_UNION", unionValue, AssertNumericCollectionUnion);
        RoundTripFile(fory, "DATA_FILE_COLLECTION_ARRAY", collectionsArray, AssertNumericCollectionsArray);
        RoundTripFile(fory, "DATA_FILE_COLLECTION_ARRAY_UNION", arrayUnion, AssertNumericCollectionArrayUnion);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void OptionalTypesRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, false);
        optional_types.OptionalTypesForyRegistration.Register(fory);

        optional_types.OptionalHolder holder = BuildOptionalHolder();
        optional_types.OptionalHolder decoded = fory.Deserialize<optional_types.OptionalHolder>(fory.Serialize(holder));
        AssertOptionalHolder(holder, decoded);

        RoundTripFile(fory, "DATA_FILE_OPTIONAL_TYPES", holder, AssertOptionalHolder);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void AnyRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, false);
        any_example.AnyExampleForyRegistration.Register(fory);

        any_example.AnyHolder holder = BuildAnyHolder();
        any_example.AnyHolder decoded = fory.Deserialize<any_example.AnyHolder>(fory.Serialize(holder));
        AssertAnyHolder(holder, decoded);

        RoundTripFile(fory, "DATA_FILE_ANY", holder, AssertAnyHolder);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void AnyProtoRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, false);
        any_example_pb.AnyExamplePbForyRegistration.Register(fory);

        any_example_pb.AnyHolder holder = BuildAnyProtoHolder();
        any_example_pb.AnyHolder decoded = fory.Deserialize<any_example_pb.AnyHolder>(fory.Serialize(holder));
        AssertAnyProtoHolder(holder, decoded);

        RoundTripFile(fory, "DATA_FILE_ANY_PROTO", holder, AssertAnyProtoHolder);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void FlatbuffersRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, false);
        monster.MonsterForyRegistration.Register(fory);
        complex_fbs.ComplexFbsForyRegistration.Register(fory);

        monster.Monster monsterValue = BuildMonster();
        monster.Monster monsterDecoded = fory.Deserialize<monster.Monster>(fory.Serialize(monsterValue));
        AssertMonster(monsterValue, monsterDecoded);

        complex_fbs.Container container = BuildContainer();
        complex_fbs.Container containerDecoded = fory.Deserialize<complex_fbs.Container>(fory.Serialize(container));
        AssertContainer(container, containerDecoded);

        RoundTripFile(fory, "DATA_FILE_FLATBUFFERS_MONSTER", monsterValue, AssertMonster);
        RoundTripFile(fory, "DATA_FILE_FLATBUFFERS_TEST2", container, AssertContainer);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void TreeRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, true);
        tree.TreeForyRegistration.Register(fory);

        tree.TreeNode treeRoot = BuildTree();
        tree.TreeNode decoded = fory.Deserialize<tree.TreeNode>(fory.Serialize(treeRoot));
        AssertTree(decoded);

        RoundTripFile(fory, "DATA_FILE_TREE", treeRoot, (_, actual) => AssertTree(actual));
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void GraphRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, true);
        graph.GraphForyRegistration.Register(fory);

        graph.Graph graphValue = BuildGraph();
        graph.Graph decoded = fory.Deserialize<graph.Graph>(fory.Serialize(graphValue));
        AssertGraph(decoded);

        RoundTripFile(fory, "DATA_FILE_GRAPH", graphValue, (_, actual) => AssertGraph(actual));
    }

    [Fact]
    public void EvolvingRoundTrip()
    {
        ForyRuntime foryV1 = BuildFory(true, false);
        ForyRuntime foryV2 = BuildFory(true, false);
        evolving1.Evolving1ForyRegistration.Register(foryV1);
        evolving2.Evolving2ForyRegistration.Register(foryV2);

        evolving1.EvolvingMessage messageV1 = new()
        {
            Id = 1,
            Name = "Alice",
            City = "NYC",
        };

        evolving2.EvolvingMessage messageV2 = foryV2.Deserialize<evolving2.EvolvingMessage>(foryV1.Serialize(messageV1));
        Assert.Equal(messageV1.Id, messageV2.Id);
        Assert.Equal(messageV1.Name, messageV2.Name);
        Assert.Equal(messageV1.City, messageV2.City);

        messageV2.Email = "alice@example.com";
        evolving1.EvolvingMessage messageV1Round =
            foryV1.Deserialize<evolving1.EvolvingMessage>(foryV2.Serialize(messageV2));
        Assert.Equal(messageV1.Id, messageV1Round.Id);
        Assert.Equal(messageV1.Name, messageV1Round.Name);
        Assert.Equal(messageV1.City, messageV1Round.City);

        evolving1.FixedMessage fixedV1 = new()
        {
            Id = 10,
            Name = "Bob",
            Score = 90,
            Note = "note",
        };

        bool fixedRoundTripMatches = false;
        try
        {
            evolving2.FixedMessage fixedV2 = foryV2.Deserialize<evolving2.FixedMessage>(foryV1.Serialize(fixedV1));
            evolving1.FixedMessage fixedV1Round =
                foryV1.Deserialize<evolving1.FixedMessage>(foryV2.Serialize(fixedV2));
            fixedRoundTripMatches =
                fixedV1Round.Id == fixedV1.Id &&
                fixedV1Round.Name == fixedV1.Name &&
                fixedV1Round.Score == fixedV1.Score &&
                fixedV1Round.Note == fixedV1.Note;
        }
        catch
        {
            fixedRoundTripMatches = false;
        }

        Assert.False(fixedRoundTripMatches);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void RootRoundTrip(bool compatible)
    {
        ForyRuntime fory = BuildFory(compatible, true);
        root.RootForyRegistration.Register(fory);

        root.MultiHolder holder = BuildRootHolder();
        root.MultiHolder decoded = fory.Deserialize<root.MultiHolder>(fory.Serialize(holder));
        AssertRootHolder(holder, decoded);

        RoundTripFile(fory, "DATA_FILE_ROOT", holder, AssertRootHolder);
    }

    [Fact]
    public void RootToBytesFromBytes()
    {
        root.MultiHolder holder = BuildRootHolder();
        byte[] payload = holder.ToBytes();
        root.MultiHolder decoded = root.MultiHolder.FromBytes(payload);
        AssertRootHolder(holder, decoded);
    }

    [Fact]
    public void ToBytesFromBytesHelpers()
    {
        addressbook.AddressBook book = BuildAddressBook();
        addressbook.AddressBook decodedBook = addressbook.AddressBook.FromBytes(book.ToBytes());
        AssertAddressBook(book, decodedBook);

        addressbook.Animal animal = addressbook.Animal.Dog(new addressbook.Dog
        {
            Name = "Rex",
            BarkVolume = 5,
        });
        addressbook.Animal decodedAnimal = addressbook.Animal.FromBytes(animal.ToBytes());
        Assert.True(decodedAnimal.IsDog);
        Assert.Equal("Rex", decodedAnimal.DogValue().Name);
        Assert.Equal(5, decodedAnimal.DogValue().BarkVolume);
    }

    private static ForyRuntime BuildFory(bool compatible, bool trackRef)
    {
        return ForyRuntime.Builder()
            .Xlang(true)
            .Compatible(compatible)
            .TrackRef(trackRef)
            .Build();
    }

    private static void RoundTripFile<T>(
        ForyRuntime fory,
        string envName,
        T expected,
        Action<T, T> assertRoundTrip)
    {
        string? dataFile = Environment.GetEnvironmentVariable(envName);
        if (string.IsNullOrWhiteSpace(dataFile))
        {
            return;
        }

        string modeFile = dataFile +
                          (fory.Config.Compatible ? ".compatible" : ".schema_consistent") +
                          (fory.Config.TrackRef ? ".track_ref" : ".no_ref");

        if (!File.Exists(modeFile) || new FileInfo(modeFile).Length == 0)
        {
            File.WriteAllBytes(modeFile, fory.Serialize(expected));
        }

        byte[] peerPayload = File.ReadAllBytes(modeFile);
        T decoded = fory.Deserialize<T>(peerPayload);
        assertRoundTrip(expected, decoded);

        byte[] output = fory.Serialize(decoded);
        File.WriteAllBytes(modeFile, output);
    }

    private static addressbook.AddressBook BuildAddressBook()
    {
        addressbook.Person.PhoneNumber mobile = new()
        {
            Number = "555-0100",
            PhoneType = addressbook.Person.PhoneType.Mobile,
        };
        addressbook.Person.PhoneNumber work = new()
        {
            Number = "555-0111",
            PhoneType = addressbook.Person.PhoneType.Work,
        };

        addressbook.Animal pet = addressbook.Animal.Dog(new addressbook.Dog
        {
            Name = "Rex",
            BarkVolume = 5,
        });
        pet = addressbook.Animal.Cat(new addressbook.Cat
        {
            Name = "Mimi",
            Lives = 9,
        });

        addressbook.Person person = new()
        {
            Name = "Alice",
            Id = 123,
            Email = "alice@example.com",
            Tags = ["friend", "colleague"],
            Scores = new Dictionary<string, int>
            {
                ["math"] = 100,
                ["science"] = 98,
            },
            Salary = 120000.5,
            Phones = [mobile, work],
            Pet = pet,
        };

        return new addressbook.AddressBook
        {
            People = [person],
            PeopleByName = new Dictionary<string, addressbook.Person>
            {
                [person.Name] = person,
            },
        };
    }

    private static auto_id.Envelope BuildEnvelope()
    {
        auto_id.Envelope.Payload payload = new()
        {
            Value = 42,
        };

        return new auto_id.Envelope
        {
            Id = "env-1",
            PayloadValue = payload,
            DetailValue = auto_id.Envelope.Detail.Payload(payload),
            Status = auto_id.Status.Ok,
        };
    }

    private static complex_pb.PrimitiveTypes BuildPrimitiveTypes()
    {
        return new complex_pb.PrimitiveTypes
        {
            BoolValue = true,
            Int8Value = 12,
            Int16Value = 1234,
            Int32Value = -123456,
            Varint32Value = -12345,
            Int64Value = -123456789,
            Varint64Value = -987654321,
            TaggedInt64Value = 123456789,
            Uint8Value = 200,
            Uint16Value = 60000,
            Uint32Value = 1234567890,
            VarUint32Value = 1234567890,
            Uint64Value = 9876543210,
            VarUint64Value = 12345678901,
            TaggedUint64Value = 2222222222,
            Float32Value = 2.5f,
            Float64Value = 3.5,
            ContactValue = complex_pb.PrimitiveTypes.Contact.Phone(12345),
        };
    }

    private static collection.NumericCollections BuildNumericCollections()
    {
        return new collection.NumericCollections
        {
            Int8Values = [1, -2, 3],
            Int16Values = [100, -200, 300],
            Int32Values = [1000, -2000, 3000],
            Int64Values = [10000, -20000, 30000],
            Uint8Values = [200, 250],
            Uint16Values = [50000, 60000],
            Uint32Values = [2000000000, 2100000000],
            Uint64Values = [9000000000, 12000000000],
            Float32Values = [1.5f, 2.5f],
            Float64Values = [3.5, 4.5],
        };
    }

    private static collection.NumericCollectionUnion BuildNumericCollectionUnion()
    {
        return collection.NumericCollectionUnion.Int32Values([7, 8, 9]);
    }

    private static collection.NumericCollectionsArray BuildNumericCollectionsArray()
    {
        return new collection.NumericCollectionsArray
        {
            Int8Values = [1, -2, 3],
            Int16Values = [100, -200, 300],
            Int32Values = [1000, -2000, 3000],
            Int64Values = [10000, -20000, 30000],
            Uint8Values = [200, 250],
            Uint16Values = [50000, 60000],
            Uint32Values = [2000000000, 2100000000],
            Uint64Values = [9000000000, 12000000000],
            Float32Values = [1.5f, 2.5f],
            Float64Values = [3.5, 4.5],
        };
    }

    private static collection.NumericCollectionArrayUnion BuildNumericCollectionArrayUnion()
    {
        return collection.NumericCollectionArrayUnion.Uint16Values([1000, 2000, 3000]);
    }

    private static optional_types.OptionalHolder BuildOptionalHolder()
    {
        DateOnly date = new(2024, 1, 2);
        DateTimeOffset timestamp = DateTimeOffset.FromUnixTimeSeconds(1704164645);
        optional_types.AllOptionalTypes all = new()
        {
            BoolValue = true,
            Int8Value = 12,
            Int16Value = 1234,
            Int32Value = -123456,
            FixedInt32Value = -123456,
            Varint32Value = -12345,
            Int64Value = -123456789,
            FixedInt64Value = -123456789,
            Varint64Value = -987654321,
            TaggedInt64Value = 123456789,
            Uint8Value = 200,
            Uint16Value = 60000,
            Uint32Value = 1234567890,
            FixedUint32Value = 1234567890,
            VarUint32Value = 1234567890,
            Uint64Value = 9876543210,
            FixedUint64Value = 9876543210,
            VarUint64Value = 12345678901,
            TaggedUint64Value = 2222222222,
            Float32Value = 2.5f,
            Float64Value = 3.5,
            StringValue = "optional",
            BytesValue = [1, 2, 3],
            DateValue = date,
            TimestampValue = timestamp,
            Int32List = [1, 2, 3],
            StringList = ["alpha", "beta"],
            Int64Map = new Dictionary<string, long>
            {
                ["alpha"] = 10,
                ["beta"] = 20,
            },
        };

        return new optional_types.OptionalHolder
        {
            AllTypes = all,
            Choice = optional_types.OptionalUnion.Note("optional"),
        };
    }

    private static any_example.AnyHolder BuildAnyHolder()
    {
        return new any_example.AnyHolder
        {
            BoolValue = true,
            StringValue = "hello",
            DateValue = new DateOnly(2024, 1, 2),
            TimestampValue = DateTimeOffset.FromUnixTimeSeconds(1704164645),
            MessageValue = new any_example.AnyInner
            {
                Name = "inner",
            },
            UnionValue = any_example.AnyUnion.Text("union"),
            ListValue = new List<string>
            {
                "alpha",
                "beta",
            },
            MapValue = new Dictionary<string, string>
            {
                ["k1"] = "v1",
                ["k2"] = "v2",
            },
        };
    }

    private static any_example_pb.AnyHolder BuildAnyProtoHolder()
    {
        any_example_pb.AnyUnion union = new()
        {
            KindValue = any_example_pb.AnyUnion.Kind.Text("proto-union"),
        };

        return new any_example_pb.AnyHolder
        {
            BoolValue = true,
            StringValue = "hello",
            DateValue = new DateOnly(2024, 1, 2),
            TimestampValue = DateTimeOffset.FromUnixTimeSeconds(1704164645),
            MessageValue = new any_example_pb.AnyInner
            {
                Name = "inner",
            },
            UnionValue = union,
            ListValue = new List<string>
            {
                "alpha",
                "beta",
            },
            MapValue = new Dictionary<string, string>
            {
                ["k1"] = "v1",
                ["k2"] = "v2",
            },
        };
    }

    private static monster.Monster BuildMonster()
    {
        return new monster.Monster
        {
            Pos = new monster.Vec3
            {
                X = 1.0f,
                Y = 2.0f,
                Z = 3.0f,
            },
            Mana = 200,
            Hp = 80,
            Name = "Orc",
            Friendly = true,
            Inventory = [1, 2, 3],
            Color = monster.Color.Blue,
        };
    }

    private static complex_fbs.Container BuildContainer()
    {
        return new complex_fbs.Container
        {
            Id = 9876543210,
            Status = complex_fbs.Status.Started,
            Bytes = [1, 2, 3],
            Numbers = [10, 20, 30],
            Scalars = new complex_fbs.ScalarPack
            {
                B = -8,
                Ub = 200,
                S = -1234,
                Us = 40000,
                I = -123456,
                Ui = 123456,
                L = -123456789,
                Ul = 987654321,
                F = 1.5f,
                D = 2.5,
                Ok = true,
            },
            Names = ["alpha", "beta"],
            Flags = [true, false],
            Payload = complex_fbs.Payload.Metric(new complex_fbs.Metric
            {
                Value = 42.0,
            }),
        };
    }

    private static tree.TreeNode BuildTree()
    {
        tree.TreeNode childA = new()
        {
            Id = "child-a",
            Name = "child-a",
            Children = [],
        };
        tree.TreeNode childB = new()
        {
            Id = "child-b",
            Name = "child-b",
            Children = [],
        };

        childA.Parent = childB;
        childB.Parent = childA;

        return new tree.TreeNode
        {
            Id = "root",
            Name = "root",
            Children = [childA, childA, childB],
        };
    }

    private static graph.Graph BuildGraph()
    {
        graph.Node node1 = new()
        {
            Id = "n1",
        };
        graph.Node node2 = new()
        {
            Id = "n2",
        };

        graph.Edge edge12 = new()
        {
            Id = "e12",
            Weight = 1.5f,
            From = node1,
            To = node2,
        };
        graph.Edge edge21 = new()
        {
            Id = "e21",
            Weight = 2.5f,
            From = node2,
            To = node1,
        };

        node1.OutEdges = [edge12];
        node1.InEdges = [edge21];
        node2.OutEdges = [edge21];
        node2.InEdges = [edge12];

        return new graph.Graph
        {
            Nodes = [node1, node2],
            Edges = [edge12, edge21],
        };
    }

    private static root.MultiHolder BuildRootHolder()
    {
        addressbook.AddressBook book = BuildAddressBook();
        addressbook.Person owner = book.People[0];

        tree.TreeNode treeRoot = new()
        {
            Id = "root",
            Name = "root",
            Children = [],
        };

        return new root.MultiHolder
        {
            Book = book,
            Root = treeRoot,
            Owner = owner,
        };
    }

    private static void AssertAddressBook(addressbook.AddressBook expected, addressbook.AddressBook actual)
    {
        Assert.Single(actual.People);
        Assert.Single(actual.PeopleByName);

        addressbook.Person expectedPerson = expected.People[0];
        addressbook.Person actualPerson = actual.People[0];

        Assert.Equal(expectedPerson.Name, actualPerson.Name);
        Assert.Equal(expectedPerson.Id, actualPerson.Id);
        Assert.Equal(expectedPerson.Email, actualPerson.Email);
        Assert.Equal(expectedPerson.Tags, actualPerson.Tags);
        AssertMap(expectedPerson.Scores, actualPerson.Scores);
        Assert.Equal(expectedPerson.Salary, actualPerson.Salary);
        Assert.Equal(expectedPerson.Phones.Count, actualPerson.Phones.Count);
        Assert.Equal(expectedPerson.Phones[0].Number, actualPerson.Phones[0].Number);
        Assert.Equal(expectedPerson.Phones[0].PhoneType, actualPerson.Phones[0].PhoneType);
        Assert.True(actualPerson.Pet.IsCat);
        Assert.Equal("Mimi", actualPerson.Pet.CatValue().Name);
        Assert.Equal(9, actualPerson.Pet.CatValue().Lives);
    }

    private static void AssertEnvelope(auto_id.Envelope expected, auto_id.Envelope actual)
    {
        Assert.Equal(expected.Id, actual.Id);
        Assert.NotNull(actual.PayloadValue);
        Assert.NotNull(expected.PayloadValue);
        Assert.Equal(expected.PayloadValue.Value, actual.PayloadValue.Value);
        Assert.Equal(expected.Status, actual.Status);

        Assert.NotNull(actual.DetailValue);
        Assert.True(actual.DetailValue.IsPayload);
        Assert.Equal(expected.DetailValue.PayloadValue().Value, actual.DetailValue.PayloadValue().Value);
    }

    private static void AssertPrimitiveTypes(complex_pb.PrimitiveTypes expected, complex_pb.PrimitiveTypes actual)
    {
        Assert.Equal(expected.BoolValue, actual.BoolValue);
        Assert.Equal(expected.Int8Value, actual.Int8Value);
        Assert.Equal(expected.Int16Value, actual.Int16Value);
        Assert.Equal(expected.Int32Value, actual.Int32Value);
        Assert.Equal(expected.Varint32Value, actual.Varint32Value);
        Assert.Equal(expected.Int64Value, actual.Int64Value);
        Assert.Equal(expected.Varint64Value, actual.Varint64Value);
        Assert.Equal(expected.TaggedInt64Value, actual.TaggedInt64Value);
        Assert.Equal(expected.Uint8Value, actual.Uint8Value);
        Assert.Equal(expected.Uint16Value, actual.Uint16Value);
        Assert.Equal(expected.Uint32Value, actual.Uint32Value);
        Assert.Equal(expected.VarUint32Value, actual.VarUint32Value);
        Assert.Equal(expected.Uint64Value, actual.Uint64Value);
        Assert.Equal(expected.VarUint64Value, actual.VarUint64Value);
        Assert.Equal(expected.TaggedUint64Value, actual.TaggedUint64Value);
        Assert.Equal(expected.Float32Value, actual.Float32Value);
        Assert.Equal(expected.Float64Value, actual.Float64Value);

        Assert.NotNull(expected.ContactValue);
        Assert.NotNull(actual.ContactValue);
        Assert.Equal(expected.ContactValue.CaseId(), actual.ContactValue.CaseId());
        Assert.True(actual.ContactValue.IsPhone);
        Assert.Equal(expected.ContactValue.PhoneValue(), actual.ContactValue.PhoneValue());
    }

    private static void AssertNumericCollections(
        collection.NumericCollections expected,
        collection.NumericCollections actual)
    {
        Assert.Equal(expected.Int8Values, actual.Int8Values);
        Assert.Equal(expected.Int16Values, actual.Int16Values);
        Assert.Equal(expected.Int32Values, actual.Int32Values);
        Assert.Equal(expected.Int64Values, actual.Int64Values);
        Assert.Equal(expected.Uint8Values, actual.Uint8Values);
        Assert.Equal(expected.Uint16Values, actual.Uint16Values);
        Assert.Equal(expected.Uint32Values, actual.Uint32Values);
        Assert.Equal(expected.Uint64Values, actual.Uint64Values);
        Assert.Equal(expected.Float32Values, actual.Float32Values);
        Assert.Equal(expected.Float64Values, actual.Float64Values);
    }

    private static void AssertNumericCollectionUnion(
        collection.NumericCollectionUnion expected,
        collection.NumericCollectionUnion actual)
    {
        Assert.Equal(expected.CaseId(), actual.CaseId());
        Assert.True(actual.IsInt32Values);
        Assert.Equal(expected.Int32ValuesValue(), actual.Int32ValuesValue());
    }

    private static void AssertNumericCollectionsArray(
        collection.NumericCollectionsArray expected,
        collection.NumericCollectionsArray actual)
    {
        Assert.Equal(expected.Int8Values, actual.Int8Values);
        Assert.Equal(expected.Int16Values, actual.Int16Values);
        Assert.Equal(expected.Int32Values, actual.Int32Values);
        Assert.Equal(expected.Int64Values, actual.Int64Values);
        Assert.Equal(expected.Uint8Values, actual.Uint8Values);
        Assert.Equal(expected.Uint16Values, actual.Uint16Values);
        Assert.Equal(expected.Uint32Values, actual.Uint32Values);
        Assert.Equal(expected.Uint64Values, actual.Uint64Values);
        Assert.Equal(expected.Float32Values, actual.Float32Values);
        Assert.Equal(expected.Float64Values, actual.Float64Values);
    }

    private static void AssertNumericCollectionArrayUnion(
        collection.NumericCollectionArrayUnion expected,
        collection.NumericCollectionArrayUnion actual)
    {
        Assert.Equal(expected.CaseId(), actual.CaseId());
        Assert.True(actual.IsUint16Values);
        Assert.Equal(expected.Uint16ValuesValue(), actual.Uint16ValuesValue());
    }

    private static void AssertOptionalHolder(
        optional_types.OptionalHolder expected,
        optional_types.OptionalHolder actual)
    {
        Assert.NotNull(actual.AllTypes);
        Assert.NotNull(expected.AllTypes);

        optional_types.AllOptionalTypes e = expected.AllTypes;
        optional_types.AllOptionalTypes a = actual.AllTypes;

        Assert.Equal(e.BoolValue, a.BoolValue);
        Assert.Equal(e.Int8Value, a.Int8Value);
        Assert.Equal(e.Int16Value, a.Int16Value);
        Assert.Equal(e.Int32Value, a.Int32Value);
        Assert.Equal(e.FixedInt32Value, a.FixedInt32Value);
        Assert.Equal(e.Varint32Value, a.Varint32Value);
        Assert.Equal(e.Int64Value, a.Int64Value);
        Assert.Equal(e.FixedInt64Value, a.FixedInt64Value);
        Assert.Equal(e.Varint64Value, a.Varint64Value);
        Assert.Equal(e.TaggedInt64Value, a.TaggedInt64Value);
        Assert.Equal(e.Uint8Value, a.Uint8Value);
        Assert.Equal(e.Uint16Value, a.Uint16Value);
        Assert.Equal(e.Uint32Value, a.Uint32Value);
        Assert.Equal(e.FixedUint32Value, a.FixedUint32Value);
        Assert.Equal(e.VarUint32Value, a.VarUint32Value);
        Assert.Equal(e.Uint64Value, a.Uint64Value);
        Assert.Equal(e.FixedUint64Value, a.FixedUint64Value);
        Assert.Equal(e.VarUint64Value, a.VarUint64Value);
        Assert.Equal(e.TaggedUint64Value, a.TaggedUint64Value);
        Assert.Equal(e.Float32Value, a.Float32Value);
        Assert.Equal(e.Float64Value, a.Float64Value);
        Assert.Equal(e.StringValue, a.StringValue);
        Assert.Equal(e.BytesValue, a.BytesValue);
        Assert.Equal(e.DateValue, a.DateValue);
        Assert.Equal(e.TimestampValue, a.TimestampValue);
        Assert.Equal(e.Int32List, a.Int32List);
        Assert.Equal(e.StringList, a.StringList);
        AssertNullableMap(e.Int64Map, a.Int64Map);

        Assert.NotNull(actual.Choice);
        Assert.NotNull(expected.Choice);
        Assert.Equal(expected.Choice.CaseId(), actual.Choice.CaseId());
        Assert.True(actual.Choice.IsNote);
        Assert.Equal(expected.Choice.NoteValue(), actual.Choice.NoteValue());
    }

    private static void AssertAnyHolder(any_example.AnyHolder expected, any_example.AnyHolder actual)
    {
        Assert.True(TryAsBool(expected.BoolValue, out bool expectedBool));
        Assert.True(TryAsBool(actual.BoolValue, out bool actualBool));
        Assert.Equal(expectedBool, actualBool);

        Assert.True(TryAsString(expected.StringValue, out string? expectedString));
        Assert.True(TryAsString(actual.StringValue, out string? actualString));
        Assert.Equal(expectedString, actualString);

        Assert.True(TryAsDateOnly(expected.DateValue, out DateOnly expectedDate));
        Assert.True(TryAsDateOnly(actual.DateValue, out DateOnly actualDate));
        Assert.Equal(expectedDate, actualDate);

        Assert.True(TryAsTimestamp(expected.TimestampValue, out DateTimeOffset expectedTimestamp));
        Assert.True(TryAsTimestamp(actual.TimestampValue, out DateTimeOffset actualTimestamp));
        Assert.Equal(expectedTimestamp, actualTimestamp);

        Assert.True(TryAnyInnerName(expected.MessageValue, out string? expectedInnerName));
        Assert.True(TryAnyInnerName(actual.MessageValue, out string? actualInnerName));
        Assert.Equal(expectedInnerName, actualInnerName);

        any_example.AnyUnion actualUnion = Assert.IsType<any_example.AnyUnion>(actual.UnionValue);
        any_example.AnyUnion expectedUnion = Assert.IsType<any_example.AnyUnion>(expected.UnionValue);
        Assert.Equal(expectedUnion.CaseId(), actualUnion.CaseId());
        Assert.True(actualUnion.IsText);
        Assert.Equal(expectedUnion.TextValue(), actualUnion.TextValue());

        Assert.True(TryStringList(expected.ListValue, out List<string> expectedList));
        Assert.True(TryStringList(actual.ListValue, out List<string> actualList));
        Assert.Equal(expectedList, actualList);

        Assert.True(TryStringMap(expected.MapValue, out Dictionary<string, string> expectedMap));
        Assert.True(TryStringMap(actual.MapValue, out Dictionary<string, string> actualMap));
        AssertMap(expectedMap, actualMap);
    }

    private static void AssertAnyProtoHolder(any_example_pb.AnyHolder expected, any_example_pb.AnyHolder actual)
    {
        Assert.True(TryAsBool(expected.BoolValue, out bool expectedBool));
        Assert.True(TryAsBool(actual.BoolValue, out bool actualBool));
        Assert.Equal(expectedBool, actualBool);

        Assert.True(TryAsString(expected.StringValue, out string? expectedString));
        Assert.True(TryAsString(actual.StringValue, out string? actualString));
        Assert.Equal(expectedString, actualString);

        Assert.True(TryAsDateOnly(expected.DateValue, out DateOnly expectedDate));
        Assert.True(TryAsDateOnly(actual.DateValue, out DateOnly actualDate));
        Assert.Equal(expectedDate, actualDate);

        Assert.True(TryAsTimestamp(expected.TimestampValue, out DateTimeOffset expectedTimestamp));
        Assert.True(TryAsTimestamp(actual.TimestampValue, out DateTimeOffset actualTimestamp));
        Assert.Equal(expectedTimestamp, actualTimestamp);

        Assert.True(TryAnyInnerName(expected.MessageValue, out string? expectedInnerName));
        Assert.True(TryAnyInnerName(actual.MessageValue, out string? actualInnerName));
        Assert.Equal(expectedInnerName, actualInnerName);

        any_example_pb.AnyUnion expectedUnion = Assert.IsType<any_example_pb.AnyUnion>(expected.UnionValue);
        any_example_pb.AnyUnion actualUnion = Assert.IsType<any_example_pb.AnyUnion>(actual.UnionValue);
        Assert.NotNull(expectedUnion.KindValue);
        Assert.NotNull(actualUnion.KindValue);
        Assert.Equal(expectedUnion.KindValue!.CaseId(), actualUnion.KindValue!.CaseId());
        Assert.True(actualUnion.KindValue.IsText);
        Assert.Equal(expectedUnion.KindValue.TextValue(), actualUnion.KindValue.TextValue());

        Assert.True(TryStringList(expected.ListValue, out List<string> expectedList));
        Assert.True(TryStringList(actual.ListValue, out List<string> actualList));
        Assert.Equal(expectedList, actualList);

        Assert.True(TryStringMap(expected.MapValue, out Dictionary<string, string> expectedMap));
        Assert.True(TryStringMap(actual.MapValue, out Dictionary<string, string> actualMap));
        AssertMap(expectedMap, actualMap);
    }

    private static void AssertMonster(monster.Monster expected, monster.Monster actual)
    {
        Assert.NotNull(actual.Pos);
        Assert.NotNull(expected.Pos);
        Assert.Equal(expected.Pos.X, actual.Pos.X);
        Assert.Equal(expected.Pos.Y, actual.Pos.Y);
        Assert.Equal(expected.Pos.Z, actual.Pos.Z);
        Assert.Equal(expected.Mana, actual.Mana);
        Assert.Equal(expected.Hp, actual.Hp);
        Assert.Equal(expected.Name, actual.Name);
        Assert.Equal(expected.Friendly, actual.Friendly);
        Assert.Equal(expected.Inventory, actual.Inventory);
        Assert.Equal(expected.Color, actual.Color);
    }

    private static void AssertContainer(complex_fbs.Container expected, complex_fbs.Container actual)
    {
        Assert.Equal(expected.Id, actual.Id);
        Assert.Equal(expected.Status, actual.Status);
        Assert.Equal(expected.Bytes, actual.Bytes);
        Assert.Equal(expected.Numbers, actual.Numbers);
        Assert.NotNull(expected.Scalars);
        Assert.NotNull(actual.Scalars);
        Assert.Equal(expected.Scalars.B, actual.Scalars.B);
        Assert.Equal(expected.Scalars.Ub, actual.Scalars.Ub);
        Assert.Equal(expected.Scalars.S, actual.Scalars.S);
        Assert.Equal(expected.Scalars.Us, actual.Scalars.Us);
        Assert.Equal(expected.Scalars.I, actual.Scalars.I);
        Assert.Equal(expected.Scalars.Ui, actual.Scalars.Ui);
        Assert.Equal(expected.Scalars.L, actual.Scalars.L);
        Assert.Equal(expected.Scalars.Ul, actual.Scalars.Ul);
        Assert.Equal(expected.Scalars.F, actual.Scalars.F);
        Assert.Equal(expected.Scalars.D, actual.Scalars.D);
        Assert.Equal(expected.Scalars.Ok, actual.Scalars.Ok);
        Assert.Equal(expected.Names, actual.Names);
        Assert.Equal(expected.Flags, actual.Flags);

        Assert.NotNull(expected.Payload);
        Assert.NotNull(actual.Payload);
        Assert.Equal(expected.Payload.CaseId(), actual.Payload.CaseId());
        Assert.True(actual.Payload.IsMetric);
        Assert.Equal(expected.Payload.MetricValue().Value, actual.Payload.MetricValue().Value);
    }

    private static void AssertTree(tree.TreeNode root)
    {
        Assert.Equal("root", root.Id);
        Assert.Equal("root", root.Name);
        Assert.Equal(3, root.Children.Count);

        tree.TreeNode childAFirst = root.Children[0];
        tree.TreeNode childASecond = root.Children[1];
        tree.TreeNode childB = root.Children[2];

        Assert.Equal("child-a", childAFirst.Id);
        Assert.Equal("child-b", childB.Id);

        Assert.Same(childAFirst, childASecond);
        Assert.NotNull(childAFirst.Parent);
        Assert.NotNull(childB.Parent);
        Assert.Same(childB, childAFirst.Parent);
        Assert.Same(childAFirst, childB.Parent);
    }

    private static void AssertGraph(graph.Graph graphValue)
    {
        Assert.Equal(2, graphValue.Nodes.Count);
        Assert.Equal(2, graphValue.Edges.Count);

        Dictionary<string, graph.Node> nodes = graphValue.Nodes.ToDictionary(n => n.Id, n => n);
        Dictionary<string, graph.Edge> edges = graphValue.Edges.ToDictionary(e => e.Id, e => e);

        Assert.True(nodes.ContainsKey("n1"));
        Assert.True(nodes.ContainsKey("n2"));
        Assert.True(edges.ContainsKey("e12"));
        Assert.True(edges.ContainsKey("e21"));

        graph.Edge edge12 = edges["e12"];
        graph.Edge edge21 = edges["e21"];

        Assert.NotNull(edge12.From);
        Assert.NotNull(edge12.To);
        Assert.NotNull(edge21.From);
        Assert.NotNull(edge21.To);

        Assert.Same(nodes["n1"], edge12.From);
        Assert.Same(nodes["n2"], edge12.To);
        Assert.Same(nodes["n2"], edge21.From);
        Assert.Same(nodes["n1"], edge21.To);

        Assert.Single(nodes["n1"].OutEdges);
        Assert.Single(nodes["n1"].InEdges);
        Assert.Single(nodes["n2"].OutEdges);
        Assert.Single(nodes["n2"].InEdges);

        Assert.Same(edge12, nodes["n1"].OutEdges[0]);
        Assert.Same(edge21, nodes["n1"].InEdges[0]);
        Assert.Same(edge21, nodes["n2"].OutEdges[0]);
        Assert.Same(edge12, nodes["n2"].InEdges[0]);
    }

    private static void AssertRootHolder(root.MultiHolder expected, root.MultiHolder actual)
    {
        Assert.NotNull(actual.Book);
        Assert.NotNull(actual.Root);
        Assert.NotNull(actual.Owner);
        Assert.NotNull(expected.Book);
        Assert.NotNull(expected.Root);
        Assert.NotNull(expected.Owner);

        AssertAddressBook(expected.Book, actual.Book);

        Assert.Equal(expected.Root.Id, actual.Root.Id);
        Assert.Equal(expected.Root.Name, actual.Root.Name);
        Assert.Equal(expected.Root.Children.Count, actual.Root.Children.Count);

        Assert.Equal(expected.Owner.Name, actual.Owner.Name);
        Assert.Equal(expected.Owner.Id, actual.Owner.Id);
        Assert.Equal(expected.Owner.Email, actual.Owner.Email);
        Assert.Equal(expected.Owner.Tags, actual.Owner.Tags);
        AssertMap(expected.Owner.Scores, actual.Owner.Scores);
    }

    private static bool TryAsBool(object? value, out bool result)
    {
        if (value is bool b)
        {
            result = b;
            return true;
        }

        result = false;
        return false;
    }

    private static bool TryAsString(object? value, out string? result)
    {
        if (value is string s)
        {
            result = s;
            return true;
        }

        result = null;
        return false;
    }

    private static bool TryAsDateOnly(object? value, out DateOnly result)
    {
        if (value is DateOnly date)
        {
            result = date;
            return true;
        }

        result = default;
        return false;
    }

    private static bool TryAsTimestamp(object? value, out DateTimeOffset result)
    {
        switch (value)
        {
            case DateTimeOffset dto:
                result = dto;
                return true;
            case DateTime dateTime:
                result = new DateTimeOffset(DateTime.SpecifyKind(dateTime, DateTimeKind.Utc));
                return true;
            default:
                result = default;
                return false;
        }
    }

    private static bool TryAnyInnerName(object? value, out string? name)
    {
        switch (value)
        {
            case any_example.AnyInner inner:
                name = inner.Name;
                return true;
            case any_example_pb.AnyInner innerPb:
                name = innerPb.Name;
                return true;
            default:
                name = null;
                return false;
        }
    }

    private static bool TryStringList(object? value, out List<string> result)
    {
        switch (value)
        {
            case List<string> strList:
                result = [.. strList];
                return true;
            case IEnumerable<string> strEnumerable:
                result = [.. strEnumerable];
                return true;
            case IEnumerable<object?> objEnumerable:
            {
                List<string> normalized = [];
                foreach (object? item in objEnumerable)
                {
                    if (item is not string text)
                    {
                        result = [];
                        return false;
                    }

                    normalized.Add(text);
                }

                result = normalized;
                return true;
            }
            default:
                result = [];
                return false;
        }
    }

    private static bool TryStringMap(object? value, out Dictionary<string, string> result)
    {
        switch (value)
        {
            case Dictionary<string, string> map:
                result = new Dictionary<string, string>(map);
                return true;
            case IReadOnlyDictionary<string, string> readonlyMap:
                result = readonlyMap.ToDictionary(kv => kv.Key, kv => kv.Value);
                return true;
            case IEnumerable<KeyValuePair<object, object?>> objectPairs:
            {
                Dictionary<string, string> normalized = [];
                foreach (KeyValuePair<object, object?> pair in objectPairs)
                {
                    if (pair.Key is not string key || pair.Value is not string val)
                    {
                        result = [];
                        return false;
                    }

                    normalized[key] = val;
                }

                result = normalized;
                return true;
            }
            default:
                result = [];
                return false;
        }
    }

    private static void AssertMap<TKey, TValue>(
        IReadOnlyDictionary<TKey, TValue> expected,
        IReadOnlyDictionary<TKey, TValue> actual)
        where TKey : notnull
    {
        Assert.Equal(expected.Count, actual.Count);
        foreach (KeyValuePair<TKey, TValue> pair in expected)
        {
            Assert.True(actual.TryGetValue(pair.Key, out TValue? value));
            Assert.Equal(pair.Value, value);
        }
    }

    private static void AssertNullableMap<TKey, TValue>(
        IReadOnlyDictionary<TKey, TValue>? expected,
        IReadOnlyDictionary<TKey, TValue>? actual)
        where TKey : notnull
    {
        if (expected is null || actual is null)
        {
            Assert.Equal(expected is null, actual is null);
            return;
        }

        AssertMap(expected, actual);
    }
}
