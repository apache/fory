/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <string>
#include <vector>

#include "addressbook.h"
#include "complex_fbs.h"
#include "fory/serialization/fory.h"
#include "monster.h"

namespace {

fory::Result<std::vector<uint8_t>, fory::Error>
ReadFile(const std::string &path) {
  std::ifstream input(path, std::ios::binary);
  if (FORY_PREDICT_FALSE(!input)) {
    return fory::Unexpected(
        fory::Error::invalid("failed to open data file for reading"));
  }
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(input)),
                            std::istreambuf_iterator<char>());
  return data;
}

fory::Result<void, fory::Error> WriteFile(const std::string &path,
                                          const std::vector<uint8_t> &data) {
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  if (FORY_PREDICT_FALSE(!output)) {
    return fory::Unexpected(
        fory::Error::invalid("failed to open data file for writing"));
  }
  output.write(reinterpret_cast<const char *>(data.data()),
               static_cast<std::streamsize>(data.size()));
  if (FORY_PREDICT_FALSE(!output)) {
    return fory::Unexpected(fory::Error::invalid("failed to write data file"));
  }
  return fory::Result<void, fory::Error>();
}

fory::Result<void, fory::Error> RunRoundTrip() {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .check_struct_version(true)
                  .track_ref(false)
                  .build();

  addressbook::RegisterTypes(fory);
  monster::RegisterTypes(fory);
  complex_fbs::RegisterTypes(fory);

  addressbook::Person::PhoneNumber mobile;
  mobile.set_number("555-0100");
  mobile.set_phone_type(addressbook::Person::PhoneType::MOBILE);

  addressbook::Person::PhoneNumber work;
  work.set_number("555-0111");
  work.set_phone_type(addressbook::Person::PhoneType::WORK);

  addressbook::Person person;
  person.set_name("Alice");
  person.set_id(123);
  person.set_email("alice@example.com");
  person.set_tags({"friend", "colleague"});
  person.set_scores({{"math", 100}, {"science", 98}});
  person.set_salary(120000.5);
  person.set_phones({mobile, work});
  addressbook::Dog dog;
  dog.set_name("Rex");
  dog.set_bark_volume(5);
  person.set_pet(addressbook::Animal::dog(dog));
  addressbook::Cat cat;
  cat.set_name("Mimi");
  cat.set_lives(9);
  person.set_pet(addressbook::Animal::cat(cat));

  addressbook::AddressBook book;
  book.set_people({person});
  book.set_people_by_name({{person.name(), person}});

  FORY_TRY(bytes, fory.serialize(book));
  FORY_TRY(roundtrip, fory.deserialize<addressbook::AddressBook>(bytes.data(),
                                                                 bytes.size()));

  if (!(roundtrip == book)) {
    return fory::Unexpected(
        fory::Error::invalid("addressbook roundtrip mismatch"));
  }

  addressbook::PrimitiveTypes types;
  types.set_bool_value(true);
  types.set_int8_value(12);
  types.set_int16_value(1234);
  types.set_int32_value(-123456);
  types.set_varint32_value(-12345);
  types.set_int64_value(-123456789);
  types.set_varint64_value(-987654321);
  types.set_tagged_int64_value(123456789);
  types.set_uint8_value(200);
  types.set_uint16_value(60000);
  types.set_uint32_value(1234567890);
  types.set_var_uint32_value(1234567890);
  types.set_uint64_value(9876543210ULL);
  types.set_var_uint64_value(12345678901ULL);
  types.set_tagged_uint64_value(2222222222ULL);
  types.set_float16_value(1.5F);
  types.set_float32_value(2.5F);
  types.set_float64_value(3.5);
  types.set_contact(addressbook::PrimitiveTypes::Contact::email(
      std::string("alice@example.com")));
  types.set_contact(addressbook::PrimitiveTypes::Contact::phone(12345));

  FORY_TRY(primitive_bytes, fory.serialize(types));
  FORY_TRY(primitive_roundtrip,
           fory.deserialize<addressbook::PrimitiveTypes>(
               primitive_bytes.data(), primitive_bytes.size()));

  if (!(primitive_roundtrip == types)) {
    return fory::Unexpected(
        fory::Error::invalid("primitive types roundtrip mismatch"));
  }

  monster::Vec3 pos;
  pos.set_x(1.0F);
  pos.set_y(2.0F);
  pos.set_z(3.0F);

  monster::Monster monster_value;
  *monster_value.mutable_pos() = pos;
  monster_value.set_mana(200);
  monster_value.set_hp(80);
  monster_value.set_name("Orc");
  monster_value.set_friendly(true);
  monster_value.set_inventory({static_cast<uint8_t>(1), static_cast<uint8_t>(2),
                               static_cast<uint8_t>(3)});
  monster_value.set_color(monster::Color::Blue);

  FORY_TRY(monster_bytes, fory.serialize(monster_value));
  FORY_TRY(monster_roundtrip, fory.deserialize<monster::Monster>(
                                  monster_bytes.data(), monster_bytes.size()));

  if (!(monster_roundtrip == monster_value)) {
    return fory::Unexpected(
        fory::Error::invalid("flatbuffers monster roundtrip mismatch"));
  }

  complex_fbs::Container container;
  container.set_id(9876543210ULL);
  container.set_status(complex_fbs::Status::STARTED);
  container.set_bytes(
      {static_cast<int8_t>(1), static_cast<int8_t>(2), static_cast<int8_t>(3)});
  container.set_numbers({10, 20, 30});
  auto *scalars = container.mutable_scalars();
  scalars->set_b(-8);
  scalars->set_ub(200);
  scalars->set_s(-1234);
  scalars->set_us(40000);
  scalars->set_i(-123456);
  scalars->set_ui(123456);
  scalars->set_l(-123456789);
  scalars->set_ul(987654321);
  scalars->set_f(1.5F);
  scalars->set_d(2.5);
  scalars->set_ok(true);
  container.set_names({"alpha", "beta"});
  container.set_flags({true, false});
  complex_fbs::Note note;
  note.set_text("alpha");
  container.set_payload(complex_fbs::Payload::note(note));
  complex_fbs::Metric metric;
  metric.set_value(42.0);
  container.set_payload(complex_fbs::Payload::metric(metric));

  FORY_TRY(container_bytes, fory.serialize(container));
  FORY_TRY(container_roundtrip,
           fory.deserialize<complex_fbs::Container>(container_bytes.data(),
                                                    container_bytes.size()));

  if (!(container_roundtrip == container)) {
    return fory::Unexpected(
        fory::Error::invalid("flatbuffers container roundtrip mismatch"));
  }

  const char *data_file = std::getenv("DATA_FILE");
  if (data_file != nullptr && data_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(data_file));
    FORY_TRY(peer_book, fory.deserialize<addressbook::AddressBook>(
                            payload.data(), payload.size()));
    if (!(peer_book == book)) {
      return fory::Unexpected(fory::Error::invalid("peer payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_book));
    FORY_RETURN_IF_ERROR(WriteFile(data_file, peer_bytes));
  }

  const char *primitive_file = std::getenv("DATA_FILE_PRIMITIVES");
  if (primitive_file != nullptr && primitive_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(primitive_file));
    FORY_TRY(peer_types, fory.deserialize<addressbook::PrimitiveTypes>(
                             payload.data(), payload.size()));
    if (!(peer_types == types)) {
      return fory::Unexpected(
          fory::Error::invalid("peer primitive payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_types));
    FORY_RETURN_IF_ERROR(WriteFile(primitive_file, peer_bytes));
  }

  const char *monster_file = std::getenv("DATA_FILE_FLATBUFFERS_MONSTER");
  if (monster_file != nullptr && monster_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(monster_file));
    FORY_TRY(peer_monster, fory.deserialize<monster::Monster>(payload.data(),
                                                              payload.size()));
    if (!(peer_monster == monster_value)) {
      return fory::Unexpected(
          fory::Error::invalid("peer monster payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_monster));
    FORY_RETURN_IF_ERROR(WriteFile(monster_file, peer_bytes));
  }

  const char *container_file = std::getenv("DATA_FILE_FLATBUFFERS_TEST2");
  if (container_file != nullptr && container_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(container_file));
    FORY_TRY(peer_container, fory.deserialize<complex_fbs::Container>(
                                 payload.data(), payload.size()));
    if (!(peer_container == container)) {
      return fory::Unexpected(
          fory::Error::invalid("peer container payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_container));
    FORY_RETURN_IF_ERROR(WriteFile(container_file, peer_bytes));
  }

  return fory::Result<void, fory::Error>();
}

} // namespace

int main() {
  auto result = RunRoundTrip();
  if (!result.ok()) {
    std::cerr << "IDL roundtrip failed: " << result.error().message()
              << std::endl;
    return 1;
  }
  return 0;
}
