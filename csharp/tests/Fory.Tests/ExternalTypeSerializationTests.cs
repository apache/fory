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

using System.Collections.Concurrent;
using System.Collections.Immutable;
using Apache.Fory;
using Fory.ExternalTypes;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.Tests;

public sealed class ExternalTypeSerializationTests
{
    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void DirectRootsRoundTrip(bool compatible)
    {
        ForyRuntime fory = NewFory(compatible);
        ExternalUser user = User(1, "alice");
        ExternalPoint point = new() { X = 3, Y = -5 };

        AssertUser(user, RoundTrip(fory, user));
        Assert.Equal(point, RoundTrip(fory, point));
        Assert.Equal(point, RoundTrip<ExternalPoint?>(fory, point));
        Assert.Null(RoundTrip<ExternalPoint?>(fory, null));
        Assert.Equal(ExternalStatus.Ready, RoundTrip(fory, ExternalStatus.Ready));
        Assert.Equal(ExternalStatus.Done, RoundTrip(fory, ExternalStatus.Complete));
        Assert.Equal((ExternalStatus)99u, RoundTrip(fory, (ExternalStatus)99u));

        ExternalBox<string> box = RoundTrip(
            fory,
            new ExternalBox<string> { Value = "closed" });
        Assert.Equal("closed", box.Value);

        ExternalDerived derived = RoundTrip(
            fory,
            new ExternalDerived { Id = 2, BaseName = "base" });
        Assert.Equal(2, derived.Id);
        Assert.Equal("base", derived.BaseName);

        ExternalHiddenDerived hidden = new() { Value = 13 };
        ((ExternalHiddenBase)hidden).Value = 11;
        ExternalHiddenDerived decodedHidden = RoundTrip(fory, hidden);
        Assert.Equal(11, ((ExternalHiddenBase)decodedHidden).Value);
        Assert.Equal(0, decodedHidden.Value);

        ExternalFields fields = RoundTrip(
            fory,
            new ExternalFields { Count = 4, Name = "field", @event = 9 });
        Assert.Equal(4, fields.Count);
        Assert.Equal("field", fields.Name);
        Assert.Equal(9, fields.@event);
    }

    [Fact]
    public void RegistrationFormsWork()
    {
        ExternalUser value = User(2, "forms");

        ForyRuntime numeric = ForyRuntime.Builder().Build();
        numeric.Register<ExternalUser>(6101);
        AssertUser(value, RoundTrip(numeric, value));

        ForyRuntime dotted = ForyRuntime.Builder().Build();
        dotted.Register<ExternalUser>("external.User");
        AssertUser(value, RoundTrip(dotted, value));

        ForyRuntime split = ForyRuntime.Builder().Build();
        split.Register<ExternalUser>("external", "User");
        AssertUser(value, RoundTrip(split, value));
    }

    [Fact]
    public void ThreadSafeRootsRoundTrip()
    {
        using ThreadSafeFory fory = ForyRuntime.Builder().BuildThreadSafe();
        fory.Register<ExternalUser>(6101);

        ExternalUser decoded = fory.Deserialize<ExternalUser>(
            fory.Serialize(User(3, "thread")));

        Assert.Equal(3, decoded.Id);
        Assert.Equal("thread", decoded.Name);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void GeneratedHolderRoundTrip(bool compatible)
    {
        ForyRuntime fory = NewFory(compatible, trackRef: true);
        ExternalUser shared = User(4, "shared");
        ExternalTargetsHolder value = new()
        {
            User = shared,
            Point = new ExternalPoint { X = 7, Y = 8 },
            OptionalPoint = new ExternalPoint { X = 9, Y = 10 },
            Status = ExternalStatus.Done,
            Users = [shared],
            UsersByName = new Dictionary<string, ExternalUser>
            {
                ["shared"] = shared,
            },
        };

        ExternalTargetsHolder decoded = RoundTrip(fory, value);

        Assert.Equal(value.Point, decoded.Point);
        Assert.Equal(value.OptionalPoint, decoded.OptionalPoint);
        Assert.Equal(value.Status, decoded.Status);
        Assert.Same(decoded.User, decoded.Users[0]);
        Assert.Same(decoded.User, decoded.UsersByName["shared"]);
    }

    [Fact]
    public void SchemaDescriptorsRoundTrip()
    {
        ForyRuntime fory = NewFory(compatible: true);
        ExternalSchemaModel value = new()
        {
            FixedValue = -7,
            TaggedValue = long.MaxValue,
            ArrayValue = [1, 2, 3],
            ListValue = [4, 5],
            SetValue = [6, 7],
            NestedValue = new Dictionary<uint, List<ulong?>?>
            {
                [8] = [9, null, ulong.MaxValue],
                [10] = null,
            },
        };

        ExternalSchemaModel decoded = RoundTrip(fory, value);

        Assert.Equal(value.FixedValue, decoded.FixedValue);
        Assert.Equal(value.TaggedValue, decoded.TaggedValue);
        Assert.Equal(value.ArrayValue, decoded.ArrayValue);
        Assert.Equal(value.ListValue, decoded.ListValue);
        Assert.True(value.SetValue.SetEquals(decoded.SetValue));
        Assert.Equal(value.NestedValue[8], decoded.NestedValue[8]);
        Assert.Null(decoded.NestedValue[10]);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void EquivalentSchemasMatchBytes(bool compatible)
    {
        ForyRuntime external = NewFory(compatible);
        ForyRuntime ordinary = NewOrdinaryFory(compatible);
        ExternalUser externalUser = User(5, "wire");
        OrdinaryUser ordinaryUser = new() { Id = 5, Name = "wire" };

        Assert.Equal(
            ordinary.Serialize(ordinaryUser),
            external.Serialize(externalUser));
        Assert.Equal(
            ordinary.Serialize(new OrdinaryPoint { X = 1, Y = 2 }),
            external.Serialize(new ExternalPoint { X = 1, Y = 2 }));
        Assert.Equal(
            ordinary.Serialize(OrdinaryStatus.Complete),
            external.Serialize(ExternalStatus.Complete));

        OrdinaryTargetsHolder ordinaryHolder = new()
        {
            User = ordinaryUser,
            Point = new OrdinaryPoint { X = 1, Y = 2 },
            OptionalPoint = new OrdinaryPoint { X = 3, Y = 4 },
            Status = OrdinaryStatus.Done,
            Users = [ordinaryUser],
            UsersByName = new Dictionary<string, OrdinaryUser>
            {
                ["wire"] = ordinaryUser,
            },
        };
        ExternalTargetsHolder externalHolder = new()
        {
            User = externalUser,
            Point = new ExternalPoint { X = 1, Y = 2 },
            OptionalPoint = new ExternalPoint { X = 3, Y = 4 },
            Status = ExternalStatus.Done,
            Users = [externalUser],
            UsersByName = new Dictionary<string, ExternalUser>
            {
                ["wire"] = externalUser,
            },
        };
        Assert.Equal(
            ordinary.Serialize(ordinaryHolder),
            external.Serialize(externalHolder));
    }

    [Fact]
    public void CompatibleEvolutionWorks()
    {
        ForyRuntime newer = ForyRuntime.Builder().Compatible(true).Build();
        newer.Register<ExternalVersionTwo>(6201);
        ForyRuntime older = ForyRuntime.Builder().Compatible(true).Build();
        older.Register<ExternalVersionOne>(6201);

        ExternalVersionOne removed = older.Deserialize<ExternalVersionOne>(
            newer.Serialize(new ExternalVersionTwo
            {
                Id = 7,
                Name = "new",
                Added = 11,
            }));
        Assert.Equal(7, removed.Id);
        Assert.Equal("new", removed.Name);

        ExternalVersionTwo added = newer.Deserialize<ExternalVersionTwo>(
            older.Serialize(new ExternalVersionOne
            {
                Id = 8,
                Name = "old",
            }));
        Assert.Equal(8, added.Id);
        Assert.Equal("old", added.Name);
        Assert.Equal(0, added.Added);

        ForyRuntime renamed = ForyRuntime.Builder().Compatible(true).Build();
        renamed.Register<ExternalVersionRenamed>(6201);
        ExternalVersionRenamed decoded = renamed.Deserialize<ExternalVersionRenamed>(
            older.Serialize(new ExternalVersionOne
            {
                Id = 9,
                Name = "renamed",
            }));
        Assert.Equal(9, decoded.Identifier);
        Assert.Equal("renamed", decoded.Name);

        ForyRuntime externalOff = ForyRuntime.Builder().Compatible(true).Build();
        externalOff.Register<ExternalEvolutionOff>(6202);
        ForyRuntime ordinaryOff = ForyRuntime.Builder().Compatible(true).Build();
        ordinaryOff.Register<OrdinaryEvolutionOff>(6202);
        Assert.False(new TypeResolver().GetTypeInfo<ExternalEvolutionOff>().Evolving);
        Assert.False(new TypeResolver().GetTypeInfo<OrdinaryEvolutionOff>().Evolving);
        Assert.Equal(
            ordinaryOff.Serialize(new OrdinaryEvolutionOff { Value = 12 }),
            externalOff.Serialize(new ExternalEvolutionOff { Value = 12 }));
    }

    [Fact]
    public void TrackedGraphsRoundTrip()
    {
        ForyRuntime fory = NewFory(compatible: true, trackRef: true);

        ExternalUser self = User(10, "self");
        self.Friend = self;
        ExternalUser selfDecoded = RoundTrip(fory, self);
        Assert.Same(selfDecoded, selfDecoded.Friend);

        ExternalUser first = User(11, "first");
        ExternalUser second = User(12, "second");
        first.Links.Add(second);
        second.Friend = first;
        ExternalUser graph = RoundTrip(fory, first);
        Assert.Same(graph, graph.Links[0].Friend);

        object dynamicSelf = self;
        ExternalUser dynamicDecoded = Assert.IsType<ExternalUser>(
            RoundTrip<object>(fory, dynamicSelf));
        Assert.Same(dynamicDecoded, dynamicDecoded.Friend);
    }

    [Fact]
    public void DynamicValuesRoundTrip()
    {
        ForyRuntime fory = NewFory(compatible: true);
        ExternalUser user = User(13, "dynamic");

        AssertUser(
            user,
            Assert.IsType<ExternalUser>(RoundTrip<object>(fory, user)));

        ExternalDynamicHolder holder = new() { DynamicValue = user };
        ExternalDynamicHolder decodedHolder = RoundTrip(fory, holder);
        AssertUser(user, Assert.IsType<ExternalUser>(decodedHolder.DynamicValue));

        object list = new List<object?> { user };
        List<object?> decodedList = Assert.IsType<List<object?>>(
            RoundTrip<object>(fory, list));
        AssertUser(user, Assert.IsType<ExternalUser>(decodedList[0]));

        object map = new Dictionary<object, object?> { ["user"] = user };
        Dictionary<object, object?> decodedMap =
            Assert.IsType<Dictionary<object, object?>>(
                RoundTrip<object>(fory, map));
        AssertUser(user, Assert.IsType<ExternalUser>(decodedMap["user"]));
    }

    [Fact]
    public void CarrierRootsRoundTrip()
    {
        ForyRuntime fory = NewFory(compatible: true);
        ExternalUser first = User(14, "first");
        ExternalUser second = User(15, "second");

        Assert.Empty(RoundTrip(fory, Array.Empty<ExternalUser>()));
        AssertUsers([first, second], RoundTrip(fory, new[] { first, second }));

        Assert.Null(RoundTrip<ExternalPoint?>(fory, null));
        Assert.Equal(
            new ExternalPoint { X = 1, Y = 2 },
            RoundTrip<ExternalPoint?>(
                fory,
                new ExternalPoint { X = 1, Y = 2 }));

        Assert.Empty(RoundTrip(fory, new List<ExternalUser>()));
        AssertUsers([first, second], RoundTrip(fory, new List<ExternalUser> { first, second }));
        Assert.Empty(RoundTrip(fory, new LinkedList<ExternalUser>()));
        AssertUsers(
            [first, second],
            RoundTrip(fory, new LinkedList<ExternalUser>([first, second])));
        Assert.Empty(RoundTrip(fory, new Queue<ExternalUser>()));
        AssertUsers(
            [first, second],
            RoundTrip(fory, new Queue<ExternalUser>([first, second])));
        Assert.Empty(RoundTrip(fory, new Stack<ExternalUser>()));
        Stack<ExternalUser> stack = new();
        stack.Push(first);
        stack.Push(second);
        AssertUsers(stack, RoundTrip(fory, stack));

        Assert.Empty(RoundTrip(fory, new HashSet<ExternalUser>()));
        Assert.True(
            RoundTrip(fory, new HashSet<ExternalUser> { first, second })
                .SetEquals([first, second]));
        Assert.Empty(RoundTrip(fory, new SortedSet<ExternalUser>()));
        AssertUsers(
            [first, second],
            RoundTrip(fory, new SortedSet<ExternalUser> { second, first }));
        Assert.Empty(RoundTrip(fory, ImmutableHashSet<ExternalUser>.Empty));
        Assert.True(
            RoundTrip(fory, ImmutableHashSet.Create(first, second))
                .SetEquals([first, second]));

        Assert.Empty(RoundTrip(fory, new Dictionary<ExternalUser, string>()));
        Dictionary<ExternalUser, string> dictionary = RoundTrip(
            fory,
            new Dictionary<ExternalUser, string> { [first] = "one" });
        Assert.Equal("one", dictionary[first]);

        Assert.Empty(RoundTrip(fory, new SortedDictionary<ExternalUser, string>()));
        SortedDictionary<ExternalUser, string> sortedDictionary = RoundTrip(
            fory,
            new SortedDictionary<ExternalUser, string> { [first] = "one" });
        Assert.Equal("one", sortedDictionary[first]);

        Assert.Empty(RoundTrip(fory, new SortedList<ExternalUser, string>()));
        SortedList<ExternalUser, string> sortedList = RoundTrip(
            fory,
            new SortedList<ExternalUser, string> { [first] = "one" });
        Assert.Equal("one", sortedList[first]);

        Assert.Empty(RoundTrip(fory, new ConcurrentDictionary<string, ExternalUser>()));
        ConcurrentDictionary<string, ExternalUser> concurrent = RoundTrip(
            fory,
            new ConcurrentDictionary<string, ExternalUser>(
                new Dictionary<string, ExternalUser> { ["one"] = first }));
        AssertUser(first, concurrent["one"]);

        Assert.Empty(RoundTrip(fory, new NullableKeyDictionary<string, ExternalUser>()));
        NullableKeyDictionary<string, ExternalUser> nullableKeys = new()
        {
            [null!] = first,
            ["two"] = second,
        };
        NullableKeyDictionary<string, ExternalUser> decodedNullableKeys =
            RoundTrip(fory, nullableKeys);
        AssertUser(first, decodedNullableKeys[null!]);
        AssertUser(second, decodedNullableKeys["two"]);

        Dictionary<string, List<ExternalUser[]>> nested = new()
        {
            ["users"] = [new[] { first, second }],
        };
        Dictionary<string, List<ExternalUser[]>> decodedNested =
            RoundTrip(fory, nested);
        AssertUsers([first, second], decodedNested["users"][0]);
    }

    [Fact]
    public void CustomSerializerReplacesGenerated()
    {
        ExternalFields value = new() { Count = 19, Name = "custom" };
        ForyRuntime generated = ForyRuntime.Builder().Build();
        generated.Register<ExternalFields>(6106);
        byte[] generatedBytes = generated.Serialize(value);

        ForyRuntime custom = ForyRuntime.Builder().Build();
        custom.Register<ExternalFields, ExternalFieldsCustomSerializer>(6106);
        byte[] customBytes = custom.Serialize(value);
        ExternalFields decoded = custom.Deserialize<ExternalFields>(customBytes);

        Assert.NotEqual(generatedBytes, customBytes);
        Assert.Equal(value.Count, decoded.Count);
        Assert.Equal(value.Name, decoded.Name);
        Assert.Throws<InvalidDataException>(
            () => generated.Register<ExternalFields, ExternalFieldsCustomSerializer>(6107));
    }

    [Fact]
    public void DuplicateFactoryIsRejected()
    {
        InvalidOperationException error = Assert.Throws<InvalidOperationException>(
            () => TypeResolver.RegisterGeneratedStruct<
                ExternalUser,
                DuplicateExternalUserSerializer>(true, true));
        Assert.Contains(typeof(ExternalUser).ToString(), error.Message, StringComparison.Ordinal);

        ForyRuntime fory = NewFory(compatible: true);
        ExternalUser decoded = RoundTrip(fory, User(20, "installed"));
        Assert.Equal("installed", decoded.Name);
    }

    private static ForyRuntime NewFory(bool compatible, bool trackRef = false)
    {
        return ForyRuntime.Builder()
            .Compatible(compatible)
            .TrackRef(trackRef)
            .Build()
            .Register<ExternalUser>(6101)
            .Register<ExternalPoint>(6102)
            .Register<ExternalStatus>(6103)
            .Register<ExternalBox<string>>(6104)
            .Register<ExternalDerived>(6105)
            .Register<ExternalFields>(6106)
            .Register<ExternalSchemaModel>(6107)
            .Register<ExternalTargetsHolder>(6108)
            .Register<ExternalDynamicHolder>(6109)
            .Register<ExternalHiddenDerived>(6110);
    }

    private static ForyRuntime NewOrdinaryFory(bool compatible)
    {
        return ForyRuntime.Builder()
            .Compatible(compatible)
            .Build()
            .Register<OrdinaryUser>(6101)
            .Register<OrdinaryPoint>(6102)
            .Register<OrdinaryStatus>(6103)
            .Register<OrdinaryTargetsHolder>(6108);
    }

    private static ExternalUser User(int id, string name)
    {
        return new ExternalUser { Id = id, Name = name };
    }

    private static T RoundTrip<T>(ForyRuntime fory, T value)
    {
        return fory.Deserialize<T>(fory.Serialize(value));
    }

    private static void AssertUser(ExternalUser expected, ExternalUser actual)
    {
        Assert.Equal(expected.Id, actual.Id);
        Assert.Equal(expected.Name, actual.Name);
    }

    private static void AssertUsers(
        IEnumerable<ExternalUser> expected,
        IEnumerable<ExternalUser> actual)
    {
        Assert.Equal(
            expected.Select(user => (user.Id, user.Name)),
            actual.Select(user => (user.Id, user.Name)));
    }
}

internal sealed class DuplicateExternalUserSerializer : Serializer<ExternalUser>
{
    public override ExternalUser DefaultValue => null!;

    public override void WriteData(
        WriteContext context,
        in ExternalUser value,
        bool hasGenerics)
    {
        throw new NotSupportedException();
    }

    public override ExternalUser ReadData(ReadContext context)
    {
        throw new NotSupportedException();
    }
}
