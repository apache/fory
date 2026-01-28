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

#include <any>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "addressbook.h"
#include "any_example.h"
#include "complex_fbs.h"
#include "fory/serialization/any_serializer.h"
#include "fory/serialization/fory.h"
#include "graph.h"
#include "monster.h"
#include "optional_types.h"
#include "tree.h"

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

tree::TreeNode BuildTree() {
  auto child_a = std::make_shared<tree::TreeNode>();
  child_a->set_id("child-a");
  child_a->set_name("child-a");

  auto child_b = std::make_shared<tree::TreeNode>();
  child_b->set_id("child-b");
  child_b->set_name("child-b");

  child_a->set_parent(
      fory::serialization::SharedWeak<tree::TreeNode>::from(child_b));
  child_b->set_parent(
      fory::serialization::SharedWeak<tree::TreeNode>::from(child_a));

  tree::TreeNode root;
  root.set_id("root");
  root.set_name("root");
  *root.mutable_children() = {child_a, child_a, child_b};
  return root;
}

fory::Result<void, fory::Error> ValidateTree(const tree::TreeNode &root) {
  const auto &children = root.children();
  if (children.size() != 3 || children[0] != children[1] ||
      children[0] == children[2]) {
    return fory::Unexpected(fory::Error::invalid("tree children mismatch"));
  }
  auto parent_a = children[0]->parent().upgrade();
  auto parent_b = children[2]->parent().upgrade();
  if (!parent_a || !parent_b) {
    return fory::Unexpected(fory::Error::invalid("tree parent upgrade failed"));
  }
  if (parent_a != children[2] || parent_b != children[0]) {
    return fory::Unexpected(fory::Error::invalid("tree parent mismatch"));
  }
  return fory::Result<void, fory::Error>();
}

graph::Graph BuildGraph() {
  auto node_a = std::make_shared<graph::Node>();
  node_a->set_id("node-a");
  auto node_b = std::make_shared<graph::Node>();
  node_b->set_id("node-b");

  auto edge = std::make_shared<graph::Edge>();
  edge->set_id("edge-1");
  edge->set_weight(1.5F);
  edge->set_from(fory::serialization::SharedWeak<graph::Node>::from(node_a));
  edge->set_to(fory::serialization::SharedWeak<graph::Node>::from(node_b));

  *node_a->mutable_out_edges() = {edge};
  *node_a->mutable_in_edges() = {edge};
  *node_b->mutable_in_edges() = {edge};

  graph::Graph graph_value;
  *graph_value.mutable_nodes() = {node_a, node_b};
  *graph_value.mutable_edges() = {edge};
  return graph_value;
}

fory::Result<void, fory::Error> ValidateGraph(const graph::Graph &graph_value) {
  const auto &nodes = graph_value.nodes();
  const auto &edges = graph_value.edges();
  if (nodes.size() != 2 || edges.size() != 1) {
    return fory::Unexpected(fory::Error::invalid("graph size mismatch"));
  }
  const auto &node_a = nodes[0];
  const auto &node_b = nodes[1];
  const auto &edge = edges[0];
  if (node_a->out_edges().empty() || node_a->in_edges().empty()) {
    return fory::Unexpected(fory::Error::invalid("graph edge list empty"));
  }
  if (node_a->out_edges()[0] != node_a->in_edges()[0] ||
      node_a->out_edges()[0] != edge) {
    return fory::Unexpected(fory::Error::invalid("graph shared edge mismatch"));
  }
  auto from = edge->from().upgrade();
  auto to = edge->to().upgrade();
  if (!from || !to) {
    return fory::Unexpected(fory::Error::invalid("graph weak upgrade failed"));
  }
  if (from != node_a || to != node_b) {
    return fory::Unexpected(
        fory::Error::invalid("graph edge endpoints mismatch"));
  }
  return fory::Result<void, fory::Error>();
}

using StringMap = std::map<std::string, std::string>;

fory::Result<void, fory::Error> RunRoundTrip() {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .check_struct_version(true)
                  .track_ref(false)
                  .build();

  addressbook::RegisterTypes(fory);
  monster::RegisterTypes(fory);
  complex_fbs::RegisterTypes(fory);
  optional_types::RegisterTypes(fory);
  any_example::RegisterTypes(fory);

  FORY_RETURN_IF_ERROR(
      fory::serialization::register_any_type<bool>(fory.type_resolver()));
  FORY_RETURN_IF_ERROR(fory::serialization::register_any_type<std::string>(
      fory.type_resolver()));
  FORY_RETURN_IF_ERROR(
      fory::serialization::register_any_type<fory::serialization::Date>(
          fory.type_resolver()));
  FORY_RETURN_IF_ERROR(
      fory::serialization::register_any_type<fory::serialization::Timestamp>(
          fory.type_resolver()));
  FORY_RETURN_IF_ERROR(
      fory::serialization::register_any_type<any_example::AnyInner>(
          fory.type_resolver()));
  FORY_RETURN_IF_ERROR(
      fory::serialization::register_any_type<any_example::AnyUnion>(
          fory.type_resolver()));
  FORY_RETURN_IF_ERROR(
      fory::serialization::register_any_type<std::vector<std::string>>(
          fory.type_resolver()));
  FORY_RETURN_IF_ERROR(
      fory::serialization::register_any_type<StringMap>(fory.type_resolver()));

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
  *person.mutable_tags() = {"friend", "colleague"};
  *person.mutable_scores() = {{"math", 100}, {"science", 98}};
  person.set_salary(120000.5);
  *person.mutable_phones() = {mobile, work};
  addressbook::Dog dog;
  dog.set_name("Rex");
  dog.set_bark_volume(5);
  *person.mutable_pet() = addressbook::Animal::dog(dog);
  addressbook::Cat cat;
  cat.set_name("Mimi");
  cat.set_lives(9);
  *person.mutable_pet() = addressbook::Animal::cat(cat);

  addressbook::AddressBook book;
  *book.mutable_people() = {person};
  *book.mutable_people_by_name() = {{person.name(), person}};

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
  types.set_float32_value(2.5F);
  types.set_float64_value(3.5);
  *types.mutable_contact() =
      addressbook::PrimitiveTypes::Contact::email("alice@example.com");
  *types.mutable_contact() = addressbook::PrimitiveTypes::Contact::phone(12345);

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
  *monster_value.mutable_inventory() = {static_cast<uint8_t>(1),
                                        static_cast<uint8_t>(2),
                                        static_cast<uint8_t>(3)};
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
  *container.mutable_bytes() = {static_cast<int8_t>(1), static_cast<int8_t>(2),
                                static_cast<int8_t>(3)};
  *container.mutable_numbers() = {10, 20, 30};
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
  *container.mutable_names() = {"alpha", "beta"};
  *container.mutable_flags() = {true, false};
  complex_fbs::Note note;
  note.set_text("alpha");
  *container.mutable_payload() = complex_fbs::Payload::note(note);
  complex_fbs::Metric metric;
  metric.set_value(42.0);
  *container.mutable_payload() = complex_fbs::Payload::metric(metric);

  FORY_TRY(container_bytes, fory.serialize(container));
  FORY_TRY(container_roundtrip,
           fory.deserialize<complex_fbs::Container>(container_bytes.data(),
                                                    container_bytes.size()));

  if (!(container_roundtrip == container)) {
    return fory::Unexpected(
        fory::Error::invalid("flatbuffers container roundtrip mismatch"));
  }

  optional_types::AllOptionalTypes all_types;
  all_types.set_bool_value(true);
  all_types.set_int8_value(12);
  all_types.set_int16_value(1234);
  all_types.set_int32_value(-123456);
  all_types.set_fixed_int32_value(-123456);
  all_types.set_varint32_value(-12345);
  all_types.set_int64_value(-123456789);
  all_types.set_fixed_int64_value(-123456789);
  all_types.set_varint64_value(-987654321);
  all_types.set_tagged_int64_value(123456789);
  all_types.set_uint8_value(200);
  all_types.set_uint16_value(60000);
  all_types.set_uint32_value(1234567890);
  all_types.set_fixed_uint32_value(1234567890);
  all_types.set_var_uint32_value(1234567890);
  all_types.set_uint64_value(9876543210ULL);
  all_types.set_fixed_uint64_value(9876543210ULL);
  all_types.set_var_uint64_value(12345678901ULL);
  all_types.set_tagged_uint64_value(2222222222ULL);
  all_types.set_float32_value(2.5F);
  all_types.set_float64_value(3.5);
  all_types.set_string_value("optional");
  *all_types.mutable_bytes_value() = {static_cast<uint8_t>(1),
                                      static_cast<uint8_t>(2),
                                      static_cast<uint8_t>(3)};
  all_types.set_date_value(fory::serialization::Date(19724));
  all_types.set_timestamp_value(
      fory::serialization::Timestamp(std::chrono::seconds(1704164645)));
  *all_types.mutable_int32_list() = {1, 2, 3};
  *all_types.mutable_string_list() = {"alpha", "beta"};
  *all_types.mutable_int64_map() = {{"alpha", 10}, {"beta", 20}};

  optional_types::OptionalHolder holder;
  *holder.mutable_all_types() = all_types;
  *holder.mutable_choice() = optional_types::OptionalUnion::note("optional");

  FORY_TRY(optional_bytes, fory.serialize(holder));
  FORY_TRY(optional_roundtrip,
           fory.deserialize<optional_types::OptionalHolder>(
               optional_bytes.data(), optional_bytes.size()));

  if (!(optional_roundtrip == holder)) {
    return fory::Unexpected(
        fory::Error::invalid("optional types roundtrip mismatch"));
  }

  any_example::AnyInner any_inner;
  any_inner.set_name("inner");

  any_example::AnyHolder any_holder;
  any_holder.set_bool_value(std::any(true));
  any_holder.set_string_value(std::any(std::string("hello")));
  any_holder.set_date_value(std::any(fory::serialization::Date(19724)));
  any_holder.set_timestamp_value(std::any(
      fory::serialization::Timestamp(std::chrono::seconds(1704164645))));
  any_holder.set_message_value(std::any(any_inner));
  any_holder.set_union_value(std::any(any_example::AnyUnion::text("union")));
  any_holder.set_list_value(
      std::any(std::vector<std::string>{"alpha", "beta"}));
  any_holder.set_map_value(std::any(StringMap{{"k1", "v1"}, {"k2", "v2"}}));

  FORY_TRY(any_bytes, fory.serialize(any_holder));
  FORY_TRY(any_roundtrip, fory.deserialize<any_example::AnyHolder>(
                              any_bytes.data(), any_bytes.size()));

  if (!(any_roundtrip == any_holder)) {
    return fory::Unexpected(
        fory::Error::invalid("any holder roundtrip mismatch"));
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

  const char *optional_file = std::getenv("DATA_FILE_OPTIONAL_TYPES");
  if (optional_file != nullptr && optional_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(optional_file));
    FORY_TRY(peer_holder, fory.deserialize<optional_types::OptionalHolder>(
                              payload.data(), payload.size()));
    if (!(peer_holder == holder)) {
      return fory::Unexpected(
          fory::Error::invalid("peer optional payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_holder));
    FORY_RETURN_IF_ERROR(WriteFile(optional_file, peer_bytes));
  }

  auto ref_fory = fory::serialization::Fory::builder()
                      .xlang(true)
                      .check_struct_version(true)
                      .track_ref(true)
                      .build();
  tree::RegisterTypes(ref_fory);
  graph::RegisterTypes(ref_fory);

  tree::TreeNode tree_root = BuildTree();
  FORY_TRY(tree_bytes, ref_fory.serialize(tree_root));
  FORY_TRY(tree_roundtrip, ref_fory.deserialize<tree::TreeNode>(
                               tree_bytes.data(), tree_bytes.size()));
  FORY_RETURN_IF_ERROR(ValidateTree(tree_roundtrip));

  const char *tree_file = std::getenv("DATA_FILE_TREE");
  if (tree_file != nullptr && tree_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(tree_file));
    FORY_TRY(peer_tree, ref_fory.deserialize<tree::TreeNode>(payload.data(),
                                                             payload.size()));
    FORY_RETURN_IF_ERROR(ValidateTree(peer_tree));
    FORY_TRY(peer_bytes, ref_fory.serialize(peer_tree));
    FORY_RETURN_IF_ERROR(WriteFile(tree_file, peer_bytes));
  }

  graph::Graph graph_value = BuildGraph();
  FORY_TRY(graph_bytes, ref_fory.serialize(graph_value));
  FORY_TRY(graph_roundtrip, ref_fory.deserialize<graph::Graph>(
                                graph_bytes.data(), graph_bytes.size()));
  FORY_RETURN_IF_ERROR(ValidateGraph(graph_roundtrip));

  const char *graph_file = std::getenv("DATA_FILE_GRAPH");
  if (graph_file != nullptr && graph_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(graph_file));
    FORY_TRY(peer_graph, ref_fory.deserialize<graph::Graph>(payload.data(),
                                                            payload.size()));
    FORY_RETURN_IF_ERROR(ValidateGraph(peer_graph));
    FORY_TRY(peer_bytes, ref_fory.serialize(peer_graph));
    FORY_RETURN_IF_ERROR(WriteFile(graph_file, peer_bytes));
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
