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

package addressbook

import (
	"os"
	"reflect"
	"testing"
	"time"

	fory "github.com/apache/fory/go/fory"
	"github.com/apache/fory/go/fory/optional"
	complexfbs "github.com/apache/fory/integration_tests/idl_tests/go/complex_fbs"
	monster "github.com/apache/fory/integration_tests/idl_tests/go/monster"
	optionaltypes "github.com/apache/fory/integration_tests/idl_tests/go/optional_types"
)

func buildAddressBook() AddressBook {
	mobile := Person_PhoneNumber{
		Number:    "555-0100",
		PhoneType: Person_PhoneTypeMobile,
	}
	work := Person_PhoneNumber{
		Number:    "555-0111",
		PhoneType: Person_PhoneTypeWork,
	}

	pet := DogAnimal(&Dog{
		Name:       "Rex",
		BarkVolume: 5,
	})
	pet = CatAnimal(&Cat{
		Name:  "Mimi",
		Lives: 9,
	})

	person := Person{
		Name:   "Alice",
		Id:     123,
		Email:  "alice@example.com",
		Tags:   []string{"friend", "colleague"},
		Scores: map[string]int32{"math": 100, "science": 98},
		Salary: 120000.5,
		Phones: []Person_PhoneNumber{mobile, work},
		Pet:    pet,
	}

	return AddressBook{
		People:       []Person{person},
		PeopleByName: map[string]Person{person.Name: person},
	}
}

func TestAddressBookRoundTrip(t *testing.T) {
	f := fory.NewFory(fory.WithXlang(true), fory.WithRefTracking(false))
	if err := RegisterTypes(f); err != nil {
		t.Fatalf("register types: %v", err)
	}
	if err := monster.RegisterTypes(f); err != nil {
		t.Fatalf("register monster types: %v", err)
	}
	if err := complexfbs.RegisterTypes(f); err != nil {
		t.Fatalf("register flatbuffers types: %v", err)
	}
	if err := optionaltypes.RegisterTypes(f); err != nil {
		t.Fatalf("register optional types: %v", err)
	}

	book := buildAddressBook()
	runLocalRoundTrip(t, f, book)
	runFileRoundTrip(t, f, book)

	types := buildPrimitiveTypes()
	runLocalPrimitiveRoundTrip(t, f, types)
	runFilePrimitiveRoundTrip(t, f, types)

	monster := buildMonster()
	runLocalMonsterRoundTrip(t, f, monster)
	runFileMonsterRoundTrip(t, f, monster)

	container := buildContainer()
	runLocalContainerRoundTrip(t, f, container)
	runFileContainerRoundTrip(t, f, container)

	holder := buildOptionalHolder()
	runLocalOptionalRoundTrip(t, f, holder)
	runFileOptionalRoundTrip(t, f, holder)
}

func runLocalRoundTrip(t *testing.T, f *fory.Fory, book AddressBook) {
	data, err := f.Serialize(book)
	if err != nil {
		t.Fatalf("serialize: %v", err)
	}

	var out AddressBook
	if err := f.Deserialize(data, &out); err != nil {
		t.Fatalf("deserialize: %v", err)
	}

	if !reflect.DeepEqual(book, out) {
		t.Fatalf("roundtrip mismatch: %#v != %#v", book, out)
	}
}

func runFileRoundTrip(t *testing.T, f *fory.Fory, book AddressBook) {
	dataFile := os.Getenv("DATA_FILE")
	if dataFile == "" {
		return
	}
	payload, err := os.ReadFile(dataFile)
	if err != nil {
		t.Fatalf("read data file: %v", err)
	}

	var decoded AddressBook
	if err := f.Deserialize(payload, &decoded); err != nil {
		t.Fatalf("deserialize peer payload: %v", err)
	}
	if !reflect.DeepEqual(book, decoded) {
		t.Fatalf("peer payload mismatch: %#v != %#v", book, decoded)
	}

	out, err := f.Serialize(decoded)
	if err != nil {
		t.Fatalf("serialize peer payload: %v", err)
	}
	if err := os.WriteFile(dataFile, out, 0o644); err != nil {
		t.Fatalf("write data file: %v", err)
	}
}

func buildPrimitiveTypes() PrimitiveTypes {
	contact := EmailPrimitiveTypes_Contact("alice@example.com")
	contact = PhonePrimitiveTypes_Contact(12345)
	return PrimitiveTypes{
		BoolValue:         true,
		Int8Value:         12,
		Int16Value:        1234,
		Int32Value:        -123456,
		Varint32Value:     -12345,
		Int64Value:        -123456789,
		Varint64Value:     -987654321,
		TaggedInt64Value:  123456789,
		Uint8Value:        200,
		Uint16Value:       60000,
		Uint32Value:       1234567890,
		VarUint32Value:    1234567890,
		Uint64Value:       9876543210,
		VarUint64Value:    12345678901,
		TaggedUint64Value: 2222222222,
		Float32Value:      2.5,
		Float64Value:      3.5,
		Contact:           &contact,
	}
}

func runLocalPrimitiveRoundTrip(t *testing.T, f *fory.Fory, types PrimitiveTypes) {
	data, err := f.Serialize(types)
	if err != nil {
		t.Fatalf("serialize: %v", err)
	}

	var out PrimitiveTypes
	if err := f.Deserialize(data, &out); err != nil {
		t.Fatalf("deserialize: %v", err)
	}

	if !reflect.DeepEqual(types, out) {
		t.Fatalf("roundtrip mismatch: %#v != %#v", types, out)
	}
}

func runFilePrimitiveRoundTrip(t *testing.T, f *fory.Fory, types PrimitiveTypes) {
	dataFile := os.Getenv("DATA_FILE_PRIMITIVES")
	if dataFile == "" {
		return
	}
	payload, err := os.ReadFile(dataFile)
	if err != nil {
		t.Fatalf("read data file: %v", err)
	}

	var decoded PrimitiveTypes
	if err := f.Deserialize(payload, &decoded); err != nil {
		t.Fatalf("deserialize peer payload: %v", err)
	}
	if !reflect.DeepEqual(types, decoded) {
		t.Fatalf("peer payload mismatch: %#v != %#v", types, decoded)
	}

	out, err := f.Serialize(decoded)
	if err != nil {
		t.Fatalf("serialize peer payload: %v", err)
	}
	if err := os.WriteFile(dataFile, out, 0o644); err != nil {
		t.Fatalf("write data file: %v", err)
	}
}

func buildMonster() monster.Monster {
	pos := &monster.Vec3{
		X: 1.0,
		Y: 2.0,
		Z: 3.0,
	}
	return monster.Monster{
		Pos:       pos,
		Mana:      int16(200),
		Hp:        int16(80),
		Name:      "Orc",
		Friendly:  true,
		Inventory: []uint8{1, 2, 3},
		Color:     monster.ColorBlue,
	}
}

func runLocalMonsterRoundTrip(t *testing.T, f *fory.Fory, monsterValue monster.Monster) {
	data, err := f.Serialize(monsterValue)
	if err != nil {
		t.Fatalf("serialize: %v", err)
	}

	var out monster.Monster
	if err := f.Deserialize(data, &out); err != nil {
		t.Fatalf("deserialize: %v", err)
	}

	if !reflect.DeepEqual(monsterValue, out) {
		t.Fatalf("roundtrip mismatch: %#v != %#v", monsterValue, out)
	}
}

func runFileMonsterRoundTrip(t *testing.T, f *fory.Fory, monsterValue monster.Monster) {
	dataFile := os.Getenv("DATA_FILE_FLATBUFFERS_MONSTER")
	if dataFile == "" {
		return
	}
	payload, err := os.ReadFile(dataFile)
	if err != nil {
		t.Fatalf("read data file: %v", err)
	}

	var decoded monster.Monster
	if err := f.Deserialize(payload, &decoded); err != nil {
		t.Fatalf("deserialize peer payload: %v", err)
	}
	if !reflect.DeepEqual(monsterValue, decoded) {
		t.Fatalf("peer payload mismatch: %#v != %#v", monsterValue, decoded)
	}

	out, err := f.Serialize(decoded)
	if err != nil {
		t.Fatalf("serialize peer payload: %v", err)
	}
	if err := os.WriteFile(dataFile, out, 0o644); err != nil {
		t.Fatalf("write data file: %v", err)
	}
}

func buildContainer() complexfbs.Container {
	scalars := &complexfbs.ScalarPack{
		B:  -8,
		Ub: 200,
		S:  -1234,
		Us: 40000,
		I:  -123456,
		Ui: 123456,
		L:  -123456789,
		Ul: 987654321,
		F:  1.5,
		D:  2.5,
		Ok: true,
	}
	payload := complexfbs.NotePayload(&complexfbs.Note{Text: "alpha"})
	payload = complexfbs.MetricPayload(&complexfbs.Metric{Value: 42.0})
	return complexfbs.Container{
		Id:      9876543210,
		Status:  complexfbs.StatusStarted,
		Bytes:   []int8{1, 2, 3},
		Numbers: []int32{10, 20, 30},
		Scalars: scalars,
		Names:   []string{"alpha", "beta"},
		Flags:   []bool{true, false},
		Payload: payload,
	}
}

func runLocalContainerRoundTrip(t *testing.T, f *fory.Fory, container complexfbs.Container) {
	data, err := f.Serialize(container)
	if err != nil {
		t.Fatalf("serialize: %v", err)
	}

	var out complexfbs.Container
	if err := f.Deserialize(data, &out); err != nil {
		t.Fatalf("deserialize: %v", err)
	}

	if !reflect.DeepEqual(container, out) {
		t.Fatalf("roundtrip mismatch: %#v != %#v", container, out)
	}
}

func runFileContainerRoundTrip(t *testing.T, f *fory.Fory, container complexfbs.Container) {
	dataFile := os.Getenv("DATA_FILE_FLATBUFFERS_TEST2")
	if dataFile == "" {
		return
	}
	payload, err := os.ReadFile(dataFile)
	if err != nil {
		t.Fatalf("read data file: %v", err)
	}

	var decoded complexfbs.Container
	if err := f.Deserialize(payload, &decoded); err != nil {
		t.Fatalf("deserialize peer payload: %v", err)
	}
	if !reflect.DeepEqual(container, decoded) {
		t.Fatalf("peer payload mismatch: %#v != %#v", container, decoded)
	}

	out, err := f.Serialize(decoded)
	if err != nil {
		t.Fatalf("serialize peer payload: %v", err)
	}
	if err := os.WriteFile(dataFile, out, 0o644); err != nil {
		t.Fatalf("write data file: %v", err)
	}
}

func buildOptionalHolder() optionaltypes.OptionalHolder {
	dateValue := fory.Date{Year: 2024, Month: time.January, Day: 2}
	timestampValue := time.Date(2024, 1, 2, 3, 4, 5, 0, time.UTC)
	allTypes := &optionaltypes.AllOptionalTypes{
		BoolValue:         optional.Some(true),
		Int8Value:         optional.Some(int8(12)),
		Int16Value:        optional.Some(int16(1234)),
		Int32Value:        optional.Some(int32(-123456)),
		FixedInt32Value:   optional.Some(int32(-123456)),
		Varint32Value:     optional.Some(int32(-12345)),
		Int64Value:        optional.Some(int64(-123456789)),
		FixedInt64Value:   optional.Some(int64(-123456789)),
		Varint64Value:     optional.Some(int64(-987654321)),
		TaggedInt64Value:  optional.Some(int64(123456789)),
		Uint8Value:        optional.Some(uint8(200)),
		Uint16Value:       optional.Some(uint16(60000)),
		Uint32Value:       optional.Some(uint32(1234567890)),
		FixedUint32Value:  optional.Some(uint32(1234567890)),
		VarUint32Value:    optional.Some(uint32(1234567890)),
		Uint64Value:       optional.Some(uint64(9876543210)),
		FixedUint64Value:  optional.Some(uint64(9876543210)),
		VarUint64Value:    optional.Some(uint64(12345678901)),
		TaggedUint64Value: optional.Some(uint64(2222222222)),
		Float32Value:      optional.Some(float32(2.5)),
		Float64Value:      optional.Some(3.5),
		StringValue:       optional.Some("optional"),
		BytesValue:        []byte{1, 2, 3},
		DateValue:         &dateValue,
		TimestampValue:    &timestampValue,
		Int32List:         []int32{1, 2, 3},
		StringList:        []string{"alpha", "beta"},
		Int64Map:          map[string]int64{"alpha": 10, "beta": 20},
	}
	unionValue := optionaltypes.NoteOptionalUnion("optional")
	return optionaltypes.OptionalHolder{
		AllTypes: allTypes,
		Choice:   &unionValue,
	}
}

func runLocalOptionalRoundTrip(t *testing.T, f *fory.Fory, holder optionaltypes.OptionalHolder) {
	data, err := f.Serialize(holder)
	if err != nil {
		t.Fatalf("serialize: %v", err)
	}

	var out optionaltypes.OptionalHolder
	if err := f.Deserialize(data, &out); err != nil {
		t.Fatalf("deserialize: %v", err)
	}

	assertOptionalHolderEqual(t, holder, out)
}

func runFileOptionalRoundTrip(t *testing.T, f *fory.Fory, holder optionaltypes.OptionalHolder) {
	dataFile := os.Getenv("DATA_FILE_OPTIONAL_TYPES")
	if dataFile == "" {
		return
	}
	payload, err := os.ReadFile(dataFile)
	if err != nil {
		t.Fatalf("read data file: %v", err)
	}

	var decoded optionaltypes.OptionalHolder
	if err := f.Deserialize(payload, &decoded); err != nil {
		t.Fatalf("deserialize peer payload: %v", err)
	}
	assertOptionalHolderEqual(t, holder, decoded)

	out, err := f.Serialize(decoded)
	if err != nil {
		t.Fatalf("serialize peer payload: %v", err)
	}
	if err := os.WriteFile(dataFile, out, 0o644); err != nil {
		t.Fatalf("write data file: %v", err)
	}
}

func assertOptionalHolderEqual(t *testing.T, expected, actual optionaltypes.OptionalHolder) {
	t.Helper()
	if expected.AllTypes == nil || actual.AllTypes == nil {
		if expected.AllTypes != actual.AllTypes {
			t.Fatalf("optional holder all_types mismatch: %#v != %#v", expected.AllTypes, actual.AllTypes)
		}
	} else {
		assertOptionalTypesEqual(t, expected.AllTypes, actual.AllTypes)
	}
	if expected.Choice == nil || actual.Choice == nil {
		if expected.Choice != actual.Choice {
			t.Fatalf("optional holder choice mismatch: %#v != %#v", expected.Choice, actual.Choice)
		}
	} else {
		assertOptionalUnionEqual(t, *expected.Choice, *actual.Choice)
	}
}

func assertOptionalUnionEqual(t *testing.T, expected, actual optionaltypes.OptionalUnion) {
	t.Helper()
	if expected.Case() != actual.Case() {
		t.Fatalf("optional union case mismatch: %v != %v", expected.Case(), actual.Case())
	}
	switch expected.Case() {
	case optionaltypes.OptionalUnionCaseNote:
		expValue, _ := expected.AsNote()
		actValue, _ := actual.AsNote()
		if expValue != actValue {
			t.Fatalf("optional union note mismatch: %v != %v", expValue, actValue)
		}
	case optionaltypes.OptionalUnionCaseCode:
		expValue, _ := expected.AsCode()
		actValue, _ := actual.AsCode()
		if expValue != actValue {
			t.Fatalf("optional union code mismatch: %v != %v", expValue, actValue)
		}
	case optionaltypes.OptionalUnionCasePayload:
		expValue, _ := expected.AsPayload()
		actValue, _ := actual.AsPayload()
		if expValue == nil || actValue == nil {
			if expValue != actValue {
				t.Fatalf("optional union payload mismatch: %#v != %#v", expValue, actValue)
			}
			return
		}
		assertOptionalTypesEqual(t, expValue, actValue)
	default:
		t.Fatalf("unexpected optional union case: %v", expected.Case())
	}
}

func assertOptionalTypesEqual(t *testing.T, expected, actual *optionaltypes.AllOptionalTypes) {
	t.Helper()
	if expected.BoolValue != actual.BoolValue {
		t.Fatalf("bool_value mismatch: %#v != %#v", expected.BoolValue, actual.BoolValue)
	}
	if expected.Int8Value != actual.Int8Value {
		t.Fatalf("int8_value mismatch: %#v != %#v", expected.Int8Value, actual.Int8Value)
	}
	if expected.Int16Value != actual.Int16Value {
		t.Fatalf("int16_value mismatch: %#v != %#v", expected.Int16Value, actual.Int16Value)
	}
	if expected.Int32Value != actual.Int32Value {
		t.Fatalf("int32_value mismatch: %#v != %#v", expected.Int32Value, actual.Int32Value)
	}
	if expected.FixedInt32Value != actual.FixedInt32Value {
		t.Fatalf("fixed_int32_value mismatch: %#v != %#v", expected.FixedInt32Value, actual.FixedInt32Value)
	}
	if expected.Varint32Value != actual.Varint32Value {
		t.Fatalf("varint32_value mismatch: %#v != %#v", expected.Varint32Value, actual.Varint32Value)
	}
	if expected.Int64Value != actual.Int64Value {
		t.Fatalf("int64_value mismatch: %#v != %#v", expected.Int64Value, actual.Int64Value)
	}
	if expected.FixedInt64Value != actual.FixedInt64Value {
		t.Fatalf("fixed_int64_value mismatch: %#v != %#v", expected.FixedInt64Value, actual.FixedInt64Value)
	}
	if expected.Varint64Value != actual.Varint64Value {
		t.Fatalf("varint64_value mismatch: %#v != %#v", expected.Varint64Value, actual.Varint64Value)
	}
	if expected.TaggedInt64Value != actual.TaggedInt64Value {
		t.Fatalf("tagged_int64_value mismatch: %#v != %#v", expected.TaggedInt64Value, actual.TaggedInt64Value)
	}
	if expected.Uint8Value != actual.Uint8Value {
		t.Fatalf("uint8_value mismatch: %#v != %#v", expected.Uint8Value, actual.Uint8Value)
	}
	if expected.Uint16Value != actual.Uint16Value {
		t.Fatalf("uint16_value mismatch: %#v != %#v", expected.Uint16Value, actual.Uint16Value)
	}
	if expected.Uint32Value != actual.Uint32Value {
		t.Fatalf("uint32_value mismatch: %#v != %#v", expected.Uint32Value, actual.Uint32Value)
	}
	if expected.FixedUint32Value != actual.FixedUint32Value {
		t.Fatalf("fixed_uint32_value mismatch: %#v != %#v", expected.FixedUint32Value, actual.FixedUint32Value)
	}
	if expected.VarUint32Value != actual.VarUint32Value {
		t.Fatalf("var_uint32_value mismatch: %#v != %#v", expected.VarUint32Value, actual.VarUint32Value)
	}
	if expected.Uint64Value != actual.Uint64Value {
		t.Fatalf("uint64_value mismatch: %#v != %#v", expected.Uint64Value, actual.Uint64Value)
	}
	if expected.FixedUint64Value != actual.FixedUint64Value {
		t.Fatalf("fixed_uint64_value mismatch: %#v != %#v", expected.FixedUint64Value, actual.FixedUint64Value)
	}
	if expected.VarUint64Value != actual.VarUint64Value {
		t.Fatalf("var_uint64_value mismatch: %#v != %#v", expected.VarUint64Value, actual.VarUint64Value)
	}
	if expected.TaggedUint64Value != actual.TaggedUint64Value {
		t.Fatalf("tagged_uint64_value mismatch: %#v != %#v", expected.TaggedUint64Value, actual.TaggedUint64Value)
	}
	if expected.Float32Value != actual.Float32Value {
		t.Fatalf("float32_value mismatch: %#v != %#v", expected.Float32Value, actual.Float32Value)
	}
	if expected.Float64Value != actual.Float64Value {
		t.Fatalf("float64_value mismatch: %#v != %#v", expected.Float64Value, actual.Float64Value)
	}
	if expected.StringValue != actual.StringValue {
		t.Fatalf("string_value mismatch: %#v != %#v", expected.StringValue, actual.StringValue)
	}
	if !reflect.DeepEqual(expected.BytesValue, actual.BytesValue) {
		t.Fatalf("bytes_value mismatch: %#v != %#v", expected.BytesValue, actual.BytesValue)
	}
	if expected.DateValue == nil || actual.DateValue == nil {
		if expected.DateValue != actual.DateValue {
			t.Fatalf("date_value mismatch: %#v != %#v", expected.DateValue, actual.DateValue)
		}
	} else if expected.DateValue.Year != actual.DateValue.Year ||
		expected.DateValue.Month != actual.DateValue.Month ||
		expected.DateValue.Day != actual.DateValue.Day {
		t.Fatalf("date_value mismatch: %#v != %#v", expected.DateValue, actual.DateValue)
	}
	if expected.TimestampValue == nil || actual.TimestampValue == nil {
		if expected.TimestampValue != actual.TimestampValue {
			t.Fatalf("timestamp_value mismatch: %#v != %#v", expected.TimestampValue, actual.TimestampValue)
		}
	} else if !expected.TimestampValue.Equal(*actual.TimestampValue) {
		t.Fatalf("timestamp_value mismatch: %v != %v", expected.TimestampValue, actual.TimestampValue)
	}
	if !reflect.DeepEqual(expected.Int32List, actual.Int32List) {
		t.Fatalf("int32_list mismatch: %#v != %#v", expected.Int32List, actual.Int32List)
	}
	if !reflect.DeepEqual(expected.StringList, actual.StringList) {
		t.Fatalf("string_list mismatch: %#v != %#v", expected.StringList, actual.StringList)
	}
	if !reflect.DeepEqual(expected.Int64Map, actual.Int64Map) {
		t.Fatalf("int64_map mismatch: %#v != %#v", expected.Int64Map, actual.Int64Map)
	}
}
