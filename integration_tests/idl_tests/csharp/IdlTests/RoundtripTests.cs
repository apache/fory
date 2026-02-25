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

using Apache.Fory;
using ForyRuntime = Apache.Fory.Fory;

namespace Apache.Fory.IdlTests;

public sealed class RoundtripTests
{
    [Fact]
    public void AddressBookRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Xlang(true)
            .Compatible(false)
            .TrackRef(false)
            .Build();
        addressbook.AddressbookForyRegistration.Register(fory);

        addressbook.AddressBook book = BuildAddressBook();
        byte[] payload = fory.Serialize(book);
        addressbook.AddressBook decoded = fory.Deserialize<addressbook.AddressBook>(payload);

        AssertAddressBook(book, decoded);

        byte[] helperBytes = book.ToBytes();
        addressbook.AddressBook helperDecoded = addressbook.AddressBook.FromBytes(helperBytes);
        AssertAddressBook(book, helperDecoded);
    }

    [Fact]
    public void AutoIdRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Xlang(true)
            .Compatible(false)
            .TrackRef(false)
            .Build();
        auto_id.AutoIdForyRegistration.Register(fory);

        auto_id.Envelope envelope = BuildEnvelope();
        auto_id.Wrapper wrapper = auto_id.Wrapper.Envelope(envelope);

        byte[] envelopePayload = fory.Serialize(envelope);
        auto_id.Envelope envelopeDecoded = fory.Deserialize<auto_id.Envelope>(envelopePayload);
        AssertEnvelope(envelope, envelopeDecoded);

        byte[] wrapperPayload = fory.Serialize(wrapper);
        auto_id.Wrapper wrapperDecoded = fory.Deserialize<auto_id.Wrapper>(wrapperPayload);
        Assert.True(wrapperDecoded.IsEnvelope);
        AssertEnvelope(envelope, wrapperDecoded.EnvelopeValue());

        byte[] helperBytes = envelope.ToBytes();
        auto_id.Envelope helperDecoded = auto_id.Envelope.FromBytes(helperBytes);
        AssertEnvelope(envelope, helperDecoded);
    }

    [Fact]
    public void OptionalTypesRoundTrip()
    {
        ForyRuntime fory = ForyRuntime.Builder()
            .Xlang(true)
            .Compatible(true)
            .TrackRef(false)
            .Build();
        optional_types.OptionalTypesForyRegistration.Register(fory);

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
            BytesValue = [0x01, 0x02, 0x03],
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

        optional_types.OptionalHolder holder = new()
        {
            AllTypes = all,
            Choice = optional_types.OptionalUnion.Note("optional"),
        };

        byte[] payload = fory.Serialize(holder);
        optional_types.OptionalHolder decoded = fory.Deserialize<optional_types.OptionalHolder>(payload);

        Assert.NotNull(decoded.AllTypes);
        Assert.NotNull(decoded.Choice);
        Assert.Equal(all.BoolValue, decoded.AllTypes.BoolValue);
        Assert.Equal(all.Int32Value, decoded.AllTypes.Int32Value);
        Assert.Equal(all.FixedInt32Value, decoded.AllTypes.FixedInt32Value);
        Assert.Equal(all.Varint32Value, decoded.AllTypes.Varint32Value);
        Assert.Equal(all.TaggedUint64Value, decoded.AllTypes.TaggedUint64Value);
        Assert.Equal(all.StringValue, decoded.AllTypes.StringValue);
        Assert.Equal(all.BytesValue, decoded.AllTypes.BytesValue);
        Assert.Equal(all.DateValue, decoded.AllTypes.DateValue);
        Assert.Equal(all.TimestampValue, decoded.AllTypes.TimestampValue);
        Assert.Equal(all.Int32List, decoded.AllTypes.Int32List);
        Assert.Equal(all.StringList, decoded.AllTypes.StringList);
        Assert.Equal(all.Int64Map, decoded.AllTypes.Int64Map);
        Assert.True(decoded.Choice.IsNote);
        Assert.Equal("optional", decoded.Choice.NoteValue());
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

        addressbook.Animal pet = addressbook.Animal.Dog(
            new addressbook.Dog
            {
                Name = "Rex",
                BarkVolume = 5,
            });
        pet = addressbook.Animal.Cat(
            new addressbook.Cat
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
        auto_id.Envelope.Detail detail = auto_id.Envelope.Detail.Payload(payload);

        return new auto_id.Envelope
        {
            Id = "env-1",
            PayloadValue = payload,
            DetailValue = detail,
            Status = auto_id.Status.Ok,
        };
    }

    private static void AssertAddressBook(
        addressbook.AddressBook expected,
        addressbook.AddressBook actual)
    {
        Assert.Single(actual.People);
        Assert.Single(actual.PeopleByName);

        addressbook.Person expectedPerson = expected.People[0];
        addressbook.Person actualPerson = actual.People[0];

        Assert.Equal(expectedPerson.Name, actualPerson.Name);
        Assert.Equal(expectedPerson.Id, actualPerson.Id);
        Assert.Equal(expectedPerson.Email, actualPerson.Email);
        Assert.Equal(expectedPerson.Tags, actualPerson.Tags);
        Assert.Equal(expectedPerson.Scores, actualPerson.Scores);
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
        Assert.True(actual.DetailValue.IsPayload);
        Assert.Equal(
            expected.DetailValue.PayloadValue().Value,
            actual.DetailValue.PayloadValue().Value);
    }
}
