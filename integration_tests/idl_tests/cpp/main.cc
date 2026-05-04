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
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <map>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <tuple>
#include <variant>
#include <vector>

#include "fory/serialization/any_serializer.h"
#include "fory/serialization/decimal_serializers.h"
#include "fory/serialization/fory.h"
#include "fory/serialization/temporal_serializers.h"
#include "fory/serialization/union_serializer.h"
#include "fory/util/bfloat16.h"
#include "fory/util/float16.h"
#include "generated/addressbook.h"
#include "generated/any_example.h"
#include "generated/auto_id.h"
#include "generated/collection.h"
#include "generated/complex_fbs.h"
#include "generated/complex_pb.h"
#include "generated/evolving1.h"
#include "generated/evolving2.h"
#include "generated/graph.h"
#include "generated/monster.h"
#include "generated/optional_types.h"
#include "generated/root.h"
#include "generated/tree.h"

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

template <typename T> std::string VectorDebugString(const std::vector<T> &v) {
  std::ostringstream out;
  out << "[";
  for (size_t i = 0; i < v.size(); ++i) {
    if (i != 0) {
      out << ",";
    }
    out << +v[i];
  }
  out << "]";
  return out.str();
}

std::string
DescribeCollectionArray(const collection::NumericCollectionsArray &value) {
  std::ostringstream out;
  out << "int8=" << VectorDebugString(value.int8_values())
      << " int16=" << VectorDebugString(value.int16_values())
      << " int32=" << VectorDebugString(value.int32_values())
      << " int64=" << VectorDebugString(value.int64_values())
      << " uint8=" << VectorDebugString(value.uint8_values())
      << " uint16=" << VectorDebugString(value.uint16_values())
      << " uint32=" << VectorDebugString(value.uint32_values())
      << " uint64=" << VectorDebugString(value.uint64_values())
      << " float32=" << VectorDebugString(value.float32_values())
      << " float64=" << VectorDebugString(value.float64_values());
  return out.str();
}

std::string DescribeFieldTypes(const fory::serialization::TypeMeta &meta) {
  std::ostringstream out;
  const auto &fields = meta.get_field_infos();
  for (size_t i = 0; i < fields.size(); ++i) {
    if (i != 0) {
      out << ",";
    }
    out << fields[i].field_id << ":" << fields[i].field_type.type_id;
  }
  return out.str();
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

} // namespace

namespace example_peer {

enum class ExampleState : int32_t {
  UNKNOWN = 0,
  READY = 1,
  FAILED = 2,
};

struct ExampleLeaf {
  std::string label;
  int32_t count = 0;

  bool operator==(const ExampleLeaf &other) const {
    return label == other.label && count == other.count;
  }

  FORY_STRUCT(ExampleLeaf, (label, fory::F(1)), (count, fory::F(2).varint()));
};

class ExampleLeafUnion {
public:
  enum class Case : uint32_t {
    NOTE = 1,
    CODE = 2,
    LEAF = 3,
  };

  ExampleLeafUnion() = default;

  static ExampleLeafUnion note(std::string value) {
    return ExampleLeafUnion(std::in_place_type<std::string>, std::move(value));
  }

  static ExampleLeafUnion code(int32_t value) {
    return ExampleLeafUnion(std::in_place_type<int32_t>, value);
  }

  static ExampleLeafUnion leaf(ExampleLeaf value) {
    return ExampleLeafUnion(std::in_place_type<ExampleLeaf>, std::move(value));
  }

  uint32_t fory_case_id() const noexcept {
    if (std::holds_alternative<std::string>(value_)) {
      return static_cast<uint32_t>(Case::NOTE);
    }
    if (std::holds_alternative<int32_t>(value_)) {
      return static_cast<uint32_t>(Case::CODE);
    }
    return static_cast<uint32_t>(Case::LEAF);
  }

  template <class Visitor> decltype(auto) visit(Visitor &&visitor) const {
    return std::visit(std::forward<Visitor>(visitor), value_);
  }

  bool operator==(const ExampleLeafUnion &other) const {
    return value_ == other.value_;
  }

private:
  std::variant<std::string, int32_t, ExampleLeaf> value_;

  template <class T, class... Args>
  explicit ExampleLeafUnion(std::in_place_type_t<T> tag, Args &&...args)
      : value_(tag, std::forward<Args>(args)...) {}
};

class ExampleMessageUnion {
public:
  ExampleMessageUnion() = default;

  static ExampleMessageUnion
  int32_array_list(std::vector<std::vector<int32_t>> value) {
    return ExampleMessageUnion(
        std::in_place_type<std::vector<std::vector<int32_t>>>,
        std::move(value));
  }

  uint32_t fory_case_id() const noexcept { return 314; }

  template <class Visitor> decltype(auto) visit(Visitor &&visitor) const {
    return std::visit(std::forward<Visitor>(visitor), value_);
  }

  bool operator==(const ExampleMessageUnion &other) const {
    return value_ == other.value_;
  }

private:
  std::variant<std::vector<std::vector<int32_t>>> value_;

  template <class T, class... Args>
  explicit ExampleMessageUnion(std::in_place_type_t<T> tag, Args &&...args)
      : value_(tag, std::forward<Args>(args)...) {}
};

struct ExampleMessageScalars {
  bool bool_value = false;
  int8_t int8_value = 0;
  int16_t int16_value = 0;
  int32_t fixed_i32_value = 0;
  int32_t varint_i32_value = 0;
  int64_t fixed_i64_value = 0;
  int64_t varint_i64_value = 0;
  int64_t tagged_i64_value = 0;
  uint8_t uint8_value = 0;
  uint16_t uint16_value = 0;
  uint32_t fixed_u32_value = 0;
  uint32_t varint_u32_value = 0;
  uint64_t fixed_u64_value = 0;
  uint64_t varint_u64_value = 0;
  uint64_t tagged_u64_value = 0;
  fory::float16_t float16_value{};
  fory::bfloat16_t bfloat16_value{};
  float float32_value = 0.0F;
  double float64_value = 0.0;
  std::string string_value;
  std::vector<uint8_t> bytes_value;
  fory::serialization::Date date_value;
  fory::serialization::Timestamp timestamp_value;
  fory::serialization::Duration duration_value;
  fory::serialization::Decimal decimal_value;
  ExampleState enum_value = ExampleState::UNKNOWN;
  std::optional<ExampleLeaf> message_value;
  ExampleLeafUnion union_value;

  FORY_STRUCT(
      ExampleMessageScalars, (bool_value, fory::F(1)), (int8_value, fory::F(2)),
      (int16_value, fory::F(3)), (fixed_i32_value, fory::F(4).fixed()),
      (varint_i32_value, fory::F(5).varint()),
      (fixed_i64_value, fory::F(6).fixed()),
      (varint_i64_value, fory::F(7).varint()),
      (tagged_i64_value, fory::F(8).tagged()), (uint8_value, fory::F(9)),
      (uint16_value, fory::F(10)), (fixed_u32_value, fory::F(11).fixed()),
      (varint_u32_value, fory::F(12).varint()),
      (fixed_u64_value, fory::F(13).fixed()),
      (varint_u64_value, fory::F(14).varint()),
      (tagged_u64_value, fory::F(15).tagged()), (float16_value, fory::F(16)),
      (bfloat16_value, fory::F(17)), (float32_value, fory::F(18)),
      (float64_value, fory::F(19)), (string_value, fory::F(20)),
      (bytes_value, fory::F(21)), (date_value, fory::F(22)),
      (timestamp_value, fory::F(23)), (duration_value, fory::F(24)),
      (decimal_value, fory::F(25)), (enum_value, fory::F(26)),
      (message_value, fory::F(27).nullable()), (union_value, fory::F(28)));
};

struct ExampleMessageLists {
  std::vector<bool> bool_list;
  std::vector<int8_t> int8_list;
  std::vector<int16_t> int16_list;
  std::vector<int32_t> fixed_i32_list;
  std::vector<int32_t> varint_i32_list;
  std::vector<int64_t> fixed_i64_list;
  std::vector<int64_t> varint_i64_list;
  std::vector<int64_t> tagged_i64_list;
  std::vector<uint8_t> uint8_list;
  std::vector<uint16_t> uint16_list;
  std::vector<uint32_t> fixed_u32_list;
  std::vector<uint32_t> varint_u32_list;
  std::vector<uint64_t> fixed_u64_list;
  std::vector<uint64_t> varint_u64_list;
  std::vector<uint64_t> tagged_u64_list;
  std::vector<fory::float16_t> float16_list;
  std::vector<fory::bfloat16_t> bfloat16_list;
  std::vector<std::optional<fory::float16_t>> maybe_float16_list;
  std::vector<std::optional<fory::bfloat16_t>> maybe_bfloat16_list;
  std::vector<float> float32_list;
  std::vector<double> float64_list;
  std::vector<std::string> string_list;
  std::vector<std::vector<uint8_t>> bytes_list;
  std::vector<fory::serialization::Date> date_list;
  std::vector<fory::serialization::Timestamp> timestamp_list;
  std::vector<fory::serialization::Duration> duration_list;
  std::vector<fory::serialization::Decimal> decimal_list;
  std::vector<ExampleState> enum_list;
  std::vector<ExampleLeaf> message_list;
  std::vector<ExampleLeafUnion> union_list;
  std::vector<std::optional<int32_t>> maybe_fixed_i32_list;
  std::vector<std::optional<uint64_t>> maybe_uint64_list;

  FORY_STRUCT(ExampleMessageLists,
              (bool_list, fory::F(101).list(fory::T::boolean())),
              (int8_list, fory::F(102).list(fory::T::int8())),
              (int16_list, fory::F(103).list(fory::T::int16())),
              (fixed_i32_list, fory::F(104).list(fory::T::int32().fixed())),
              (varint_i32_list, fory::F(105).list(fory::T::int32().varint())),
              (fixed_i64_list, fory::F(106).list(fory::T::int64().fixed())),
              (varint_i64_list, fory::F(107).list(fory::T::int64().varint())),
              (tagged_i64_list, fory::F(108).list(fory::T::int64().tagged())),
              (uint8_list, fory::F(109).list(fory::T::uint8())),
              (uint16_list, fory::F(110).list(fory::T::uint16())),
              (fixed_u32_list, fory::F(111).list(fory::T::uint32().fixed())),
              (varint_u32_list, fory::F(112).list(fory::T::uint32().varint())),
              (fixed_u64_list, fory::F(113).list(fory::T::uint64().fixed())),
              (varint_u64_list, fory::F(114).list(fory::T::uint64().varint())),
              (tagged_u64_list, fory::F(115).list(fory::T::uint64().tagged())),
              (float16_list, fory::F(116).list(fory::T::float16())),
              (bfloat16_list, fory::F(117).list(fory::T::bfloat16())),
              (maybe_float16_list,
               fory::F(118).list(fory::T::inner(fory::T::float16()))),
              (maybe_bfloat16_list,
               fory::F(119).list(fory::T::inner(fory::T::bfloat16()))),
              (float32_list, fory::F(120).list(fory::T::float32())),
              (float64_list, fory::F(121).list(fory::T::float64())),
              (string_list, fory::F(122).list(fory::T::string())),
              (bytes_list, fory::F(123).list(fory::FieldNodeSpec{})),
              (date_list, fory::F(124).list(fory::FieldNodeSpec{})),
              (timestamp_list, fory::F(125).list(fory::FieldNodeSpec{})),
              (duration_list, fory::F(126).list(fory::FieldNodeSpec{})),
              (decimal_list, fory::F(127).list(fory::FieldNodeSpec{})),
              (enum_list, fory::F(128).list(fory::FieldNodeSpec{})),
              (message_list, fory::F(129).list(fory::FieldNodeSpec{})),
              (union_list, fory::F(130).list(fory::FieldNodeSpec{})),
              (maybe_fixed_i32_list,
               fory::F(131).list(fory::T::inner(fory::T::int32().fixed()))),
              (maybe_uint64_list,
               fory::F(132).list(fory::T::inner(fory::T::uint64().varint()))));
};

struct ExampleMessageArraysMaps {
  std::vector<bool> bool_array;
  std::vector<int8_t> int8_array;
  std::vector<int16_t> int16_array;
  std::vector<int32_t> int32_array;
  std::vector<int64_t> int64_array;
  std::vector<uint8_t> uint8_array;
  std::vector<uint16_t> uint16_array;
  std::vector<uint32_t> uint32_array;
  std::vector<uint64_t> uint64_array;
  std::vector<fory::float16_t> float16_array;
  std::vector<fory::bfloat16_t> bfloat16_array;
  std::vector<float> float32_array;
  std::vector<double> float64_array;
  std::vector<std::vector<int32_t>> int32_array_list;
  std::vector<std::vector<uint8_t>> uint8_array_list;
  std::map<bool, std::string> string_values_by_bool;
  std::map<int8_t, std::string> string_values_by_int8;
  std::map<int16_t, std::string> string_values_by_int16;
  std::map<int32_t, std::string> string_values_by_fixed_i32;
  std::map<int32_t, std::string> string_values_by_varint_i32;
  std::map<int64_t, std::string> string_values_by_fixed_i64;
  std::map<int64_t, std::string> string_values_by_varint_i64;
  std::map<int64_t, std::string> string_values_by_tagged_i64;
  std::map<uint8_t, std::string> string_values_by_uint8;
  std::map<uint16_t, std::string> string_values_by_uint16;
  std::map<uint32_t, std::string> string_values_by_fixed_u32;
  std::map<uint32_t, std::string> string_values_by_varint_u32;
  std::map<uint64_t, std::string> string_values_by_fixed_u64;
  std::map<uint64_t, std::string> string_values_by_varint_u64;
  std::map<uint64_t, std::string> string_values_by_tagged_u64;
  std::map<std::string, std::string> string_values_by_string;
  std::map<fory::serialization::Timestamp, std::string>
      string_values_by_timestamp;
  std::map<fory::serialization::Duration, std::string>
      string_values_by_duration;
  std::map<ExampleState, std::string> string_values_by_enum;
  std::map<std::string, fory::float16_t> float16_values_by_name;
  std::map<std::string, fory::float16_t> maybe_float16_values_by_name;
  std::map<std::string, fory::bfloat16_t> bfloat16_values_by_name;
  std::map<std::string, fory::bfloat16_t> maybe_bfloat16_values_by_name;
  std::map<std::string, std::vector<uint8_t>> bytes_values_by_name;
  std::map<std::string, fory::serialization::Date> date_values_by_name;
  std::map<std::string, fory::serialization::Decimal> decimal_values_by_name;
  std::map<std::string, ExampleLeaf> message_values_by_name;
  std::map<std::string, ExampleLeafUnion> union_values_by_name;
  std::map<std::string, std::vector<uint8_t>> uint8_array_values_by_name;
  std::map<std::string, std::vector<float>> float32_array_values_by_name;
  std::map<std::string, std::vector<int32_t>> int32_array_values_by_name;

  FORY_STRUCT(
      ExampleMessageArraysMaps,
      (bool_array, fory::F(301).array(fory::T::boolean())),
      (int8_array, fory::F(302).array(fory::T::int8())),
      (int16_array, fory::F(303).array(fory::T::int16())),
      (int32_array, fory::F(304).array(fory::T::int32())),
      (int64_array, fory::F(305).array(fory::T::int64())),
      (uint8_array, fory::F(306).array(fory::T::uint8())),
      (uint16_array, fory::F(307).array(fory::T::uint16())),
      (uint32_array, fory::F(308).array(fory::T::uint32())),
      (uint64_array, fory::F(309).array(fory::T::uint64())),
      (float16_array, fory::F(310).array(fory::T::float16())),
      (bfloat16_array, fory::F(311).array(fory::T::bfloat16())),
      (float32_array, fory::F(312).array(fory::T::float32())),
      (float64_array, fory::F(313).array(fory::T::float64())),
      (int32_array_list, fory::F(314).list(fory::T::array(fory::T::int32()))),
      (uint8_array_list, fory::F(315).list(fory::T::array(fory::T::uint8()))),
      (string_values_by_bool,
       fory::F(201).map(fory::T::boolean(), fory::T::string())),
      (string_values_by_int8,
       fory::F(202).map(fory::T::int8(), fory::T::string())),
      (string_values_by_int16,
       fory::F(203).map(fory::T::int16(), fory::T::string())),
      (string_values_by_fixed_i32,
       fory::F(204).map(fory::T::int32().fixed(), fory::T::string())),
      (string_values_by_varint_i32,
       fory::F(205).map(fory::T::int32().varint(), fory::T::string())),
      (string_values_by_fixed_i64,
       fory::F(206).map(fory::T::int64().fixed(), fory::T::string())),
      (string_values_by_varint_i64,
       fory::F(207).map(fory::T::int64().varint(), fory::T::string())),
      (string_values_by_tagged_i64,
       fory::F(208).map(fory::T::int64().tagged(), fory::T::string())),
      (string_values_by_uint8,
       fory::F(209).map(fory::T::uint8(), fory::T::string())),
      (string_values_by_uint16,
       fory::F(210).map(fory::T::uint16(), fory::T::string())),
      (string_values_by_fixed_u32,
       fory::F(211).map(fory::T::uint32().fixed(), fory::T::string())),
      (string_values_by_varint_u32,
       fory::F(212).map(fory::T::uint32().varint(), fory::T::string())),
      (string_values_by_fixed_u64,
       fory::F(213).map(fory::T::uint64().fixed(), fory::T::string())),
      (string_values_by_varint_u64,
       fory::F(214).map(fory::T::uint64().varint(), fory::T::string())),
      (string_values_by_tagged_u64,
       fory::F(215).map(fory::T::uint64().tagged(), fory::T::string())),
      (string_values_by_string,
       fory::F(218).map(fory::T::string(), fory::T::string())),
      (string_values_by_timestamp,
       fory::F(219).map(fory::FieldNodeSpec{}, fory::T::string())),
      (string_values_by_duration,
       fory::F(220).map(fory::FieldNodeSpec{}, fory::T::string())),
      (string_values_by_enum,
       fory::F(221).map(fory::FieldNodeSpec{}, fory::T::string())),
      (float16_values_by_name,
       fory::F(222).map(fory::T::string(), fory::T::float16())),
      (maybe_float16_values_by_name,
       fory::F(223).map(fory::T::string(), fory::T::inner(fory::T::float16()))),
      (bfloat16_values_by_name,
       fory::F(224).map(fory::T::string(), fory::T::bfloat16())),
      (maybe_bfloat16_values_by_name,
       fory::F(225).map(fory::T::string(),
                        fory::T::inner(fory::T::bfloat16()))),
      (bytes_values_by_name,
       fory::F(226).map(fory::T::string(), fory::FieldNodeSpec{})),
      (date_values_by_name,
       fory::F(227).map(fory::T::string(), fory::FieldNodeSpec{})),
      (decimal_values_by_name,
       fory::F(228).map(fory::T::string(), fory::FieldNodeSpec{})),
      (message_values_by_name,
       fory::F(229).map(fory::T::string(), fory::FieldNodeSpec{})),
      (union_values_by_name,
       fory::F(230).map(fory::T::string(), fory::FieldNodeSpec{})),
      (uint8_array_values_by_name,
       fory::F(231).map(fory::T::string(), fory::T::array(fory::T::uint8()))),
      (float32_array_values_by_name,
       fory::F(232).map(fory::T::string(), fory::T::array(fory::T::float32()))),
      (int32_array_values_by_name,
       fory::F(233).map(fory::T::string(), fory::T::array(fory::T::int32()))));
};

struct ExampleMessage : ExampleMessageScalars,
                        ExampleMessageLists,
                        ExampleMessageArraysMaps {

  bool operator==(const ExampleMessage &other) const {
    return std::tie(
               bool_value, int8_value, int16_value, fixed_i32_value,
               varint_i32_value, fixed_i64_value, varint_i64_value,
               tagged_i64_value, uint8_value, uint16_value, fixed_u32_value,
               varint_u32_value, fixed_u64_value, varint_u64_value,
               tagged_u64_value, float16_value, bfloat16_value, float32_value,
               float64_value, string_value, bytes_value, date_value,
               timestamp_value, duration_value, decimal_value, enum_value,
               message_value, union_value, bool_list, int8_list, int16_list,
               fixed_i32_list, varint_i32_list, fixed_i64_list, varint_i64_list,
               tagged_i64_list, uint8_list, uint16_list, fixed_u32_list,
               varint_u32_list, fixed_u64_list, varint_u64_list,
               tagged_u64_list, float16_list, bfloat16_list, maybe_float16_list,
               maybe_bfloat16_list, float32_list, float64_list, string_list,
               bytes_list, date_list, timestamp_list, duration_list,
               decimal_list, enum_list, message_list, union_list,
               maybe_fixed_i32_list, maybe_uint64_list, bool_array, int8_array,
               int16_array, int32_array, int64_array, uint8_array, uint16_array,
               uint32_array, uint64_array, float16_array, bfloat16_array,
               float32_array, float64_array, int32_array_list, uint8_array_list,
               string_values_by_bool, string_values_by_int8,
               string_values_by_int16, string_values_by_fixed_i32,
               string_values_by_varint_i32, string_values_by_fixed_i64,
               string_values_by_varint_i64, string_values_by_tagged_i64,
               string_values_by_uint8, string_values_by_uint16,
               string_values_by_fixed_u32, string_values_by_varint_u32,
               string_values_by_fixed_u64, string_values_by_varint_u64,
               string_values_by_tagged_u64, string_values_by_string,
               string_values_by_timestamp, string_values_by_duration,
               string_values_by_enum, float16_values_by_name,
               maybe_float16_values_by_name, bfloat16_values_by_name,
               maybe_bfloat16_values_by_name, bytes_values_by_name,
               date_values_by_name, decimal_values_by_name,
               message_values_by_name, union_values_by_name,
               uint8_array_values_by_name, float32_array_values_by_name,
               int32_array_values_by_name) ==
           std::tie(
               other.bool_value, other.int8_value, other.int16_value,
               other.fixed_i32_value, other.varint_i32_value,
               other.fixed_i64_value, other.varint_i64_value,
               other.tagged_i64_value, other.uint8_value, other.uint16_value,
               other.fixed_u32_value, other.varint_u32_value,
               other.fixed_u64_value, other.varint_u64_value,
               other.tagged_u64_value, other.float16_value,
               other.bfloat16_value, other.float32_value, other.float64_value,
               other.string_value, other.bytes_value, other.date_value,
               other.timestamp_value, other.duration_value, other.decimal_value,
               other.enum_value, other.message_value, other.union_value,
               other.bool_list, other.int8_list, other.int16_list,
               other.fixed_i32_list, other.varint_i32_list,
               other.fixed_i64_list, other.varint_i64_list,
               other.tagged_i64_list, other.uint8_list, other.uint16_list,
               other.fixed_u32_list, other.varint_u32_list,
               other.fixed_u64_list, other.varint_u64_list,
               other.tagged_u64_list, other.float16_list, other.bfloat16_list,
               other.maybe_float16_list, other.maybe_bfloat16_list,
               other.float32_list, other.float64_list, other.string_list,
               other.bytes_list, other.date_list, other.timestamp_list,
               other.duration_list, other.decimal_list, other.enum_list,
               other.message_list, other.union_list, other.maybe_fixed_i32_list,
               other.maybe_uint64_list, other.bool_array, other.int8_array,
               other.int16_array, other.int32_array, other.int64_array,
               other.uint8_array, other.uint16_array, other.uint32_array,
               other.uint64_array, other.float16_array, other.bfloat16_array,
               other.float32_array, other.float64_array, other.int32_array_list,
               other.uint8_array_list, other.string_values_by_bool,
               other.string_values_by_int8, other.string_values_by_int16,
               other.string_values_by_fixed_i32,
               other.string_values_by_varint_i32,
               other.string_values_by_fixed_i64,
               other.string_values_by_varint_i64,
               other.string_values_by_tagged_i64, other.string_values_by_uint8,
               other.string_values_by_uint16, other.string_values_by_fixed_u32,
               other.string_values_by_varint_u32,
               other.string_values_by_fixed_u64,
               other.string_values_by_varint_u64,
               other.string_values_by_tagged_u64, other.string_values_by_string,
               other.string_values_by_timestamp,
               other.string_values_by_duration, other.string_values_by_enum,
               other.float16_values_by_name, other.maybe_float16_values_by_name,
               other.bfloat16_values_by_name,
               other.maybe_bfloat16_values_by_name, other.bytes_values_by_name,
               other.date_values_by_name, other.decimal_values_by_name,
               other.message_values_by_name, other.union_values_by_name,
               other.uint8_array_values_by_name,
               other.float32_array_values_by_name,
               other.int32_array_values_by_name);
  }

  FORY_STRUCT(ExampleMessage, FORY_BASE(ExampleMessageScalars),
              FORY_BASE(ExampleMessageLists),
              FORY_BASE(ExampleMessageArraysMaps));
};

FORY_ENUM(ExampleState, UNKNOWN, READY, FAILED);
FORY_UNION_IDS(ExampleLeafUnion, 1, 2, 3);
FORY_UNION_CASE(ExampleLeafUnion, 1, std::string, ExampleLeafUnion::note,
                fory::F(1));
FORY_UNION_CASE(ExampleLeafUnion, 2, int32_t, ExampleLeafUnion::code,
                fory::F(2).varint());
FORY_UNION_CASE(ExampleLeafUnion, 3, ExampleLeaf, ExampleLeafUnion::leaf,
                fory::F(3));
FORY_UNION_IDS(ExampleMessageUnion, 314);
FORY_UNION_CASE(ExampleMessageUnion, 314, std::vector<std::vector<int32_t>>,
                ExampleMessageUnion::int32_array_list,
                fory::F(314).list(fory::T::array(fory::T::int32())));

} // namespace example_peer

FORY_STRUCT_EVOLVING(example_peer::ExampleLeaf, false);
FORY_STRUCT_EVOLVING(example_peer::ExampleMessage, true);

namespace {

fory::Result<void, fory::Error>
RegisterExampleTypes(fory::serialization::BaseFory &fory) {
  FORY_RETURN_IF_ERROR(fory.register_enum<example_peer::ExampleState>(1504));
  FORY_RETURN_IF_ERROR(
      fory.register_union<example_peer::ExampleLeafUnion>(1503));
  FORY_RETURN_IF_ERROR(
      fory.register_union<example_peer::ExampleMessageUnion>(1501));
  FORY_RETURN_IF_ERROR(fory.register_struct<example_peer::ExampleLeaf>(1502));
  FORY_RETURN_IF_ERROR(
      fory.register_struct<example_peer::ExampleMessage>(1500));
  return fory::Result<void, fory::Error>();
}

example_peer::ExampleLeaf BuildExampleLeaf(std::string label = "leaf",
                                           int32_t count = 7) {
  return example_peer::ExampleLeaf{std::move(label), count};
}

example_peer::ExampleMessage BuildExampleMessage() {
  auto leaf = BuildExampleLeaf();
  auto other_leaf = BuildExampleLeaf("other", 8);
  auto leaf_union = example_peer::ExampleLeafUnion::leaf(other_leaf);
  auto f16 = [](float value) { return fory::float16_t::from_float(value); };
  auto bf16 = [](float value) { return fory::bfloat16_t::from_float(value); };
  auto ts = [](int64_t seconds) {
    return fory::serialization::Timestamp(std::chrono::seconds(seconds));
  };
  using fory::serialization::Date;
  using fory::serialization::Decimal;
  using fory::serialization::Duration;
  example_peer::ExampleMessage message;
  message.bool_value = true;
  message.int8_value = -12;
  message.int16_value = -1234;
  message.fixed_i32_value = -123456;
  message.varint_i32_value = -12345;
  message.fixed_i64_value = -123456789;
  message.varint_i64_value = -987654321;
  message.tagged_i64_value = 123456789;
  message.uint8_value = static_cast<uint8_t>(200);
  message.uint16_value = 60000;
  message.fixed_u32_value = 1234567890;
  message.varint_u32_value = 1234567890;
  message.fixed_u64_value = 9876543210ULL;
  message.varint_u64_value = 12345678901ULL;
  message.tagged_u64_value = 2222222222ULL;
  message.float16_value = fory::float16_t::from_float(1.5F);
  message.bfloat16_value = fory::bfloat16_t::from_float(2.5F);
  message.float32_value = 3.5F;
  message.float64_value = 4.5;
  message.string_value = "example";
  message.bytes_value = {static_cast<uint8_t>(1), static_cast<uint8_t>(2),
                         static_cast<uint8_t>(3)};
  message.date_value = Date(19756);
  message.timestamp_value = ts(1706933106);
  message.duration_value = std::chrono::seconds(42) + Duration(7000);
  message.decimal_value = Decimal::from_int64(12345, 2);
  message.enum_value = example_peer::ExampleState::READY;
  message.message_value = leaf;
  message.union_value = leaf_union;
  message.bool_list = {true, false, true};
  message.int8_list = {1, -2, 3};
  message.int16_list = {100, -200, 300};
  message.fixed_i32_list = {1000, -2000, 3000};
  message.varint_i32_list = {-10, 20, -30};
  message.fixed_i64_list = {10000, -20000};
  message.varint_i64_list = {-40, 50};
  message.tagged_i64_list = {60, 70};
  message.uint8_list = {static_cast<uint8_t>(200), static_cast<uint8_t>(250)};
  message.uint16_list = {50000, 60000};
  message.fixed_u32_list = {2000000000U, 2100000000U};
  message.varint_u32_list = {100, 200};
  message.fixed_u64_list = {9000000000ULL};
  message.varint_u64_list = {12000000000ULL};
  message.tagged_u64_list = {13000000000ULL};
  message.float16_list = {fory::float16_t::from_bits(0x3C00),
                          fory::float16_t::from_bits(0x4000)};
  message.bfloat16_list = {fory::bfloat16_t::from_bits(0x3F80),
                           fory::bfloat16_t::from_bits(0x4000)};
  message.maybe_float16_list = {f16(1.0F), std::nullopt, f16(2.0F)};
  message.maybe_bfloat16_list = {bf16(1.0F), std::nullopt, bf16(3.0F)};
  message.float32_list = {1.5F, 2.5F};
  message.float64_list = {3.5, 4.5};
  message.string_list = {"alpha", "beta"};
  message.bytes_list = {{static_cast<uint8_t>(4), static_cast<uint8_t>(5)},
                        {static_cast<uint8_t>(6), static_cast<uint8_t>(7)}};
  message.date_list = {Date(19723), Date(19724)};
  message.timestamp_list = {ts(1704067200), ts(1704153600)};
  message.duration_list = {std::chrono::milliseconds(1),
                           std::chrono::seconds(2)};
  message.decimal_list = {Decimal::from_int64(125, 2),
                          Decimal::from_int64(250, 2)};
  message.enum_list = {example_peer::ExampleState::UNKNOWN,
                       example_peer::ExampleState::FAILED};
  message.message_list = {leaf, other_leaf};
  message.union_list = {example_peer::ExampleLeafUnion::note("note"),
                        leaf_union};
  message.maybe_fixed_i32_list = {1, std::nullopt, 3};
  message.maybe_uint64_list = {10ULL, std::nullopt, 30ULL};
  message.bool_array = {true, false};
  message.int8_array = {1, -2};
  message.int16_array = {100, -200};
  message.int32_array = {1000, -2000};
  message.int64_array = {10000, -20000};
  message.uint8_array = {static_cast<uint8_t>(200), static_cast<uint8_t>(250)};
  message.uint16_array = {50000, 60000};
  message.uint32_array = {2000000000U, 2100000000U};
  message.uint64_array = {9000000000ULL, 12000000000ULL};
  message.float16_array = {f16(1.0F), f16(2.0F)};
  message.bfloat16_array = {bf16(1.0F), bf16(2.0F)};
  message.float32_array = {1.5F, 2.5F};
  message.float64_array = {3.5, 4.5};
  message.int32_array_list = {{1, 2}, {3, 4}};
  message.uint8_array_list = {
      {static_cast<uint8_t>(201), static_cast<uint8_t>(202)},
      {static_cast<uint8_t>(203)}};
  message.string_values_by_bool = {{true, "bool"}};
  message.string_values_by_int8 = {{-1, "int8"}};
  message.string_values_by_int16 = {{-2, "int16"}};
  message.string_values_by_fixed_i32 = {{-3, "fixed-i32"}};
  message.string_values_by_varint_i32 = {{4, "varint_i32"}};
  message.string_values_by_fixed_i64 = {{-5, "fixed-i64"}};
  message.string_values_by_varint_i64 = {{6, "varint_i64"}};
  message.string_values_by_tagged_i64 = {{7, "tagged-i64"}};
  message.string_values_by_uint8 = {{static_cast<uint8_t>(200), "uint8"}};
  message.string_values_by_uint16 = {{60000, "uint16"}};
  message.string_values_by_fixed_u32 = {{1234567890U, "fixed-u32"}};
  message.string_values_by_varint_u32 = {{1234567891U, "varint-u32"}};
  message.string_values_by_fixed_u64 = {{9876543210ULL, "fixed-u64"}};
  message.string_values_by_varint_u64 = {{9876543211ULL, "varint-u64"}};
  message.string_values_by_tagged_u64 = {{9876543212ULL, "tagged-u64"}};
  message.string_values_by_string = {{"name", "value"}};
  message.string_values_by_timestamp = {{ts(1709528767), "time"}};
  message.string_values_by_duration = {{std::chrono::seconds(9), "duration"}};
  message.string_values_by_enum = {
      {example_peer::ExampleState::READY, "ready"}};
  message.float16_values_by_name = {{"f16", f16(1.25F)}};
  message.maybe_float16_values_by_name = {{"maybe-f16", f16(1.5F)}};
  message.bfloat16_values_by_name = {{"bf16", bf16(1.75F)}};
  message.maybe_bfloat16_values_by_name = {{"maybe-bf16", bf16(2.25F)}};
  message.bytes_values_by_name = {
      {"bytes", {static_cast<uint8_t>(8), static_cast<uint8_t>(9)}}};
  message.date_values_by_name = {{"date", Date(19849)}};
  message.decimal_values_by_name = {{"decimal", Decimal::from_int64(9901, 2)}};
  message.message_values_by_name = {{"leaf", leaf}};
  message.union_values_by_name = {
      {"union", example_peer::ExampleLeafUnion::code(42)}};
  message.uint8_array_values_by_name = {
      {"u8", {static_cast<uint8_t>(201), static_cast<uint8_t>(202)}}};
  message.float32_array_values_by_name = {{"f32", {1.25F, 2.5F}}};
  message.int32_array_values_by_name = {{"i32", {101, 202}}};
  return message;
}

example_peer::ExampleMessageUnion BuildExampleMessageUnion() {
  return example_peer::ExampleMessageUnion::int32_array_list(
      {{11, 12}, {13, 14}});
}

fory::Result<void, fory::Error> RunEvolvingRoundTrip() {
  auto fory_v1 = fory::serialization::Fory::builder()
                     .xlang(true)
                     .compatible(true)
                     .check_struct_version(false)
                     .track_ref(false)
                     .build();
  auto fory_v2 = fory::serialization::Fory::builder()
                     .xlang(true)
                     .compatible(true)
                     .check_struct_version(false)
                     .track_ref(false)
                     .build();
  evolving1::register_types(fory_v1);
  evolving2::register_types(fory_v2);

  evolving1::EvolvingMessage msg_v1;
  msg_v1.set_id(1);
  msg_v1.set_name("Alice");
  msg_v1.set_city("NYC");

  FORY_TRY(bytes, fory_v1.serialize(msg_v1));
  FORY_TRY(decoded, fory_v2.deserialize<evolving2::EvolvingMessage>(bytes));
  if (decoded.id() != msg_v1.id() || decoded.name() != msg_v1.name() ||
      decoded.city() != msg_v1.city()) {
    return fory::Unexpected(fory::Error::invalid("evolving message mismatch"));
  }
  decoded.set_email("alice@example.com");

  FORY_TRY(round_bytes, fory_v2.serialize(decoded));
  FORY_TRY(round_trip,
           fory_v1.deserialize<evolving1::EvolvingMessage>(round_bytes));
  if (!(round_trip == msg_v1)) {
    return fory::Unexpected(
        fory::Error::invalid("evolving roundtrip mismatch"));
  }

  evolving1::FixedMessage fixed_v1;
  fixed_v1.set_id(10);
  fixed_v1.set_name("Bob");
  fixed_v1.set_score(90);
  fixed_v1.set_note("note");

  FORY_TRY(fixed_bytes, fory_v1.serialize(fixed_v1));
  auto fixed_v2 = fory_v2.deserialize<evolving2::FixedMessage>(fixed_bytes);
  if (!fixed_v2.ok()) {
    return fory::Result<void, fory::Error>();
  }
  auto fixed_round = fory_v2.serialize(fixed_v2.value());
  if (!fixed_round.ok()) {
    return fory::Result<void, fory::Error>();
  }
  auto fixed_back =
      fory_v1.deserialize<evolving1::FixedMessage>(fixed_round.value());
  if (!fixed_back.ok()) {
    return fory::Result<void, fory::Error>();
  }
  if (fixed_back.value() == fixed_v1) {
    return fory::Unexpected(
        fory::Error::invalid("fixed message unexpectedly compatible"));
  }

  evolving1::EvolvingSizeMessage evolving_size_v1;
  evolving_size_v1.set_payload("payload");
  evolving1::FixedSizeMessage fixed_size_v1;
  fixed_size_v1.set_payload("payload");

  FORY_TRY(evolving_size_bytes, fory_v1.serialize(evolving_size_v1));
  FORY_TRY(fixed_size_bytes, fory_v1.serialize(fixed_size_v1));
  if (fixed_size_bytes.size() >= evolving_size_bytes.size()) {
    return fory::Unexpected(
        fory::Error::invalid("fixed size message was not smaller"));
  }

  FORY_TRY(
      evolving_size_decoded,
      fory_v2.deserialize<evolving2::EvolvingSizeMessage>(evolving_size_bytes));
  if (evolving_size_decoded.payload() != evolving_size_v1.payload()) {
    return fory::Unexpected(
        fory::Error::invalid("evolving size payload mismatch"));
  }
  FORY_TRY(evolving_size_round_bytes, fory_v2.serialize(evolving_size_decoded));
  FORY_TRY(evolving_size_round,
           fory_v1.deserialize<evolving1::EvolvingSizeMessage>(
               evolving_size_round_bytes));
  if (!(evolving_size_round == evolving_size_v1)) {
    return fory::Unexpected(
        fory::Error::invalid("evolving size roundtrip mismatch"));
  }

  FORY_TRY(fixed_size_decoded,
           fory_v2.deserialize<evolving2::FixedSizeMessage>(fixed_size_bytes));
  if (fixed_size_decoded.payload() != fixed_size_v1.payload()) {
    return fory::Unexpected(
        fory::Error::invalid("fixed size payload mismatch"));
  }
  FORY_TRY(fixed_size_round_bytes, fory_v2.serialize(fixed_size_decoded));
  FORY_TRY(fixed_size_round, fory_v1.deserialize<evolving1::FixedSizeMessage>(
                                 fixed_size_round_bytes));
  if (!(fixed_size_round == fixed_size_v1)) {
    return fory::Unexpected(
        fory::Error::invalid("fixed size roundtrip mismatch"));
  }

  return fory::Result<void, fory::Error>();
}

using StringMap = std::map<std::string, std::string>;

fory::Result<void, fory::Error> RunRoundTrip(bool compatible) {
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)
                  .compatible(compatible)
                  .check_struct_version(!compatible)
                  .track_ref(false)
                  .build();

  complex_pb::register_types(fory);
  addressbook::register_types(fory);
  auto_id::register_types(fory);
  monster::register_types(fory);
  complex_fbs::register_types(fory);
  collection::register_types(fory);
  optional_types::register_types(fory);
  any_example::register_types(fory);
  FORY_RETURN_IF_ERROR(RegisterExampleTypes(fory));

  if (compatible) {
    FORY_RETURN_IF_ERROR(RunEvolvingRoundTrip());
  }

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

  FORY_TRY(book_bytes, book.to_bytes());
  FORY_TRY(book_roundtrip_bytes,
           addressbook::AddressBook::from_bytes(book_bytes));
  if (!(book_roundtrip_bytes == book)) {
    return fory::Unexpected(
        fory::Error::invalid("addressbook to_bytes roundtrip mismatch"));
  }

  addressbook::Animal animal = addressbook::Animal::dog(dog);
  FORY_TRY(animal_bytes, animal.to_bytes());
  FORY_TRY(animal_roundtrip, addressbook::Animal::from_bytes(animal_bytes));
  if (!(animal_roundtrip == animal)) {
    return fory::Unexpected(
        fory::Error::invalid("animal to_bytes roundtrip mismatch"));
  }

  auto_id::Envelope::Payload auto_payload;
  auto_payload.set_value(42);
  auto_id::Envelope::Detail auto_detail =
      auto_id::Envelope::Detail::payload(auto_payload);
  auto_id::Envelope auto_envelope;
  auto_envelope.set_id("env-1");
  *auto_envelope.mutable_payload() = auto_payload;
  *auto_envelope.mutable_detail() = auto_detail;
  auto_envelope.set_status(auto_id::Status::OK);

  FORY_TRY(auto_bytes, fory.serialize(auto_envelope));
  FORY_TRY(auto_roundtrip, fory.deserialize<auto_id::Envelope>(
                               auto_bytes.data(), auto_bytes.size()));
  if (!(auto_roundtrip == auto_envelope)) {
    return fory::Unexpected(
        fory::Error::invalid("auto_id envelope roundtrip mismatch"));
  }

  auto_id::Envelope auto_envelope_for_wrapper;
  auto_envelope_for_wrapper.set_id(auto_envelope.id());
  if (auto_envelope.has_payload()) {
    *auto_envelope_for_wrapper.mutable_payload() = auto_envelope.payload();
  }
  *auto_envelope_for_wrapper.mutable_detail() = auto_envelope.detail();
  auto_envelope_for_wrapper.set_status(auto_envelope.status());

  auto_id::Wrapper auto_wrapper =
      auto_id::Wrapper::envelope(std::move(auto_envelope_for_wrapper));
  FORY_TRY(auto_wrapper_bytes, fory.serialize(auto_wrapper));
  FORY_TRY(auto_wrapper_roundtrip,
           fory.deserialize<auto_id::Wrapper>(auto_wrapper_bytes.data(),
                                              auto_wrapper_bytes.size()));
  if (!(auto_wrapper_roundtrip == auto_wrapper)) {
    return fory::Unexpected(
        fory::Error::invalid("auto_id wrapper roundtrip mismatch"));
  }

  addressbook::Person multi_owner;
  multi_owner.set_name("Alice");
  multi_owner.set_id(123);
  addressbook::Dog multi_dog;
  multi_dog.set_name("Rex");
  multi_dog.set_bark_volume(5);
  *multi_owner.mutable_pet() = addressbook::Animal::dog(multi_dog);

  addressbook::AddressBook multi_book;
  *multi_book.mutable_people() = {multi_owner};
  *multi_book.mutable_people_by_name() = {{multi_owner.name(), multi_owner}};

  tree::TreeNode multi_root;
  multi_root.set_id("root");
  multi_root.set_name("root");

  root::MultiHolder multi;
  *multi.mutable_book() = multi_book;
  *multi.mutable_root() = multi_root;
  *multi.mutable_owner() = multi_owner;

  FORY_TRY(multi_bytes, multi.to_bytes());
  FORY_TRY(multi_roundtrip, root::MultiHolder::from_bytes(multi_bytes));
  if (!(multi_roundtrip == multi)) {
    return fory::Unexpected(
        fory::Error::invalid("root to_bytes roundtrip mismatch"));
  }

  FORY_TRY(bytes, fory.serialize(book));
  FORY_TRY(roundtrip, fory.deserialize<addressbook::AddressBook>(bytes.data(),
                                                                 bytes.size()));

  if (!(roundtrip == book)) {
    return fory::Unexpected(
        fory::Error::invalid("addressbook roundtrip mismatch"));
  }

  complex_pb::PrimitiveTypes types;
  types.set_bool_value(true);
  types.set_int8_value(12);
  types.set_int16_value(1234);
  types.set_int32_value(-123456);
  types.set_varint_i32_value(-12345);
  types.set_int64_value(-123456789);
  types.set_varint_i64_value(-987654321);
  types.set_tagged_i64_value(123456789);
  types.set_uint8_value(200);
  types.set_uint16_value(60000);
  types.set_uint32_value(1234567890);
  types.set_varint_u32_value(1234567890);
  types.set_uint64_value(9876543210ULL);
  types.set_varint_u64_value(12345678901ULL);
  types.set_tagged_u64_value(2222222222ULL);
  types.set_float32_value(2.5F);
  types.set_float64_value(3.5);
  *types.mutable_contact() =
      complex_pb::PrimitiveTypes::Contact::email("alice@example.com");
  *types.mutable_contact() = complex_pb::PrimitiveTypes::Contact::phone(12345);

  FORY_TRY(primitive_bytes, fory.serialize(types));
  FORY_TRY(primitive_roundtrip,
           fory.deserialize<complex_pb::PrimitiveTypes>(
               primitive_bytes.data(), primitive_bytes.size()));

  if (!(primitive_roundtrip == types)) {
    return fory::Unexpected(
        fory::Error::invalid("primitive types roundtrip mismatch"));
  }

  collection::NumericCollections collections;
  *collections.mutable_int8_values() = {
      static_cast<int8_t>(1), static_cast<int8_t>(-2), static_cast<int8_t>(3)};
  *collections.mutable_int16_values() = {static_cast<int16_t>(100),
                                         static_cast<int16_t>(-200),
                                         static_cast<int16_t>(300)};
  *collections.mutable_int32_values() = {1000, -2000, 3000};
  *collections.mutable_int64_values() = {10000, -20000, 30000};
  *collections.mutable_uint8_values() = {static_cast<uint8_t>(200),
                                         static_cast<uint8_t>(250)};
  *collections.mutable_uint16_values() = {static_cast<uint16_t>(50000),
                                          static_cast<uint16_t>(60000)};
  *collections.mutable_uint32_values() = {2000000000U, 2100000000U};
  *collections.mutable_uint64_values() = {9000000000ULL, 12000000000ULL};
  *collections.mutable_float32_values() = {1.5F, 2.5F};
  *collections.mutable_float64_values() = {3.5, 4.5};

  collection::NumericCollectionUnion collection_union =
      collection::NumericCollectionUnion::int32_values(
          std::vector<int32_t>{7, 8, 9});

  collection::NumericCollectionsArray collections_array;
  *collections_array.mutable_int8_values() = {
      static_cast<int8_t>(1), static_cast<int8_t>(-2), static_cast<int8_t>(3)};
  *collections_array.mutable_int16_values() = {static_cast<int16_t>(100),
                                               static_cast<int16_t>(-200),
                                               static_cast<int16_t>(300)};
  *collections_array.mutable_int32_values() = {1000, -2000, 3000};
  *collections_array.mutable_int64_values() = {10000, -20000, 30000};
  *collections_array.mutable_uint8_values() = {static_cast<uint8_t>(200),
                                               static_cast<uint8_t>(250)};
  *collections_array.mutable_uint16_values() = {static_cast<uint16_t>(50000),
                                                static_cast<uint16_t>(60000)};
  *collections_array.mutable_uint32_values() = {2000000000U, 2100000000U};
  *collections_array.mutable_uint64_values() = {9000000000ULL, 12000000000ULL};
  *collections_array.mutable_float32_values() = {1.5F, 2.5F};
  *collections_array.mutable_float64_values() = {3.5, 4.5};

  collection::NumericCollectionArrayUnion collection_array_union =
      collection::NumericCollectionArrayUnion::uint16_values(
          std::vector<uint16_t>{1000, 2000, 3000});

  FORY_TRY(collection_bytes, fory.serialize(collections));
  FORY_TRY(collection_roundtrip,
           fory.deserialize<collection::NumericCollections>(
               collection_bytes.data(), collection_bytes.size()));
  if (!(collection_roundtrip == collections)) {
    return fory::Unexpected(
        fory::Error::invalid("collection roundtrip mismatch"));
  }

  FORY_TRY(collection_union_bytes, fory.serialize(collection_union));
  FORY_TRY(collection_union_roundtrip,
           fory.deserialize<collection::NumericCollectionUnion>(
               collection_union_bytes.data(), collection_union_bytes.size()));
  if (!(collection_union_roundtrip == collection_union)) {
    return fory::Unexpected(
        fory::Error::invalid("collection union roundtrip mismatch"));
  }

  FORY_TRY(collection_array_bytes, fory.serialize(collections_array));
  FORY_TRY(collection_array_roundtrip,
           fory.deserialize<collection::NumericCollectionsArray>(
               collection_array_bytes.data(), collection_array_bytes.size()));
  if (!(collection_array_roundtrip == collections_array)) {
    return fory::Unexpected(
        fory::Error::invalid("collection array roundtrip mismatch"));
  }

  FORY_TRY(collection_array_union_bytes,
           fory.serialize(collection_array_union));
  FORY_TRY(collection_array_union_roundtrip,
           fory.deserialize<collection::NumericCollectionArrayUnion>(
               collection_array_union_bytes.data(),
               collection_array_union_bytes.size()));
  if (!(collection_array_union_roundtrip == collection_array_union)) {
    return fory::Unexpected(
        fory::Error::invalid("collection array union roundtrip mismatch"));
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
  all_types.set_fixed_i32_value(-123456);
  all_types.set_varint_i32_value(-12345);
  all_types.set_int64_value(-123456789);
  all_types.set_fixed_i64_value(-123456789);
  all_types.set_varint_i64_value(-987654321);
  all_types.set_tagged_i64_value(123456789);
  all_types.set_uint8_value(200);
  all_types.set_uint16_value(60000);
  all_types.set_uint32_value(1234567890);
  all_types.set_fixed_u32_value(1234567890);
  all_types.set_varint_u32_value(1234567890);
  all_types.set_uint64_value(9876543210ULL);
  all_types.set_fixed_u64_value(9876543210ULL);
  all_types.set_varint_u64_value(12345678901ULL);
  all_types.set_tagged_u64_value(2222222222ULL);
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

  example_peer::ExampleMessage example_message = BuildExampleMessage();
  FORY_TRY(example_bytes, fory.serialize(example_message));
  FORY_TRY(example_roundtrip, fory.deserialize<example_peer::ExampleMessage>(
                                  example_bytes.data(), example_bytes.size()));
  if (!(example_roundtrip == example_message)) {
    return fory::Unexpected(
        fory::Error::invalid("example message roundtrip mismatch"));
  }

  example_peer::ExampleMessageUnion example_union = BuildExampleMessageUnion();
  FORY_TRY(example_union_bytes, fory.serialize(example_union));
  FORY_TRY(example_union_roundtrip,
           fory.deserialize<example_peer::ExampleMessageUnion>(
               example_union_bytes.data(), example_union_bytes.size()));
  if (!(example_union_roundtrip == example_union)) {
    return fory::Unexpected(
        fory::Error::invalid("example message union roundtrip mismatch"));
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

  const char *auto_id_file = std::getenv("DATA_FILE_AUTO_ID");
  if (auto_id_file != nullptr && auto_id_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(auto_id_file));
    FORY_TRY(peer_envelope, fory.deserialize<auto_id::Envelope>(
                                payload.data(), payload.size()));
    if (!(peer_envelope == auto_envelope)) {
      return fory::Unexpected(
          fory::Error::invalid("peer auto_id payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_envelope));
    FORY_RETURN_IF_ERROR(WriteFile(auto_id_file, peer_bytes));
  }

  const char *primitive_file = std::getenv("DATA_FILE_PRIMITIVES");
  if (primitive_file != nullptr && primitive_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(primitive_file));
    FORY_TRY(peer_types, fory.deserialize<complex_pb::PrimitiveTypes>(
                             payload.data(), payload.size()));
    if (!(peer_types == types)) {
      return fory::Unexpected(
          fory::Error::invalid("peer primitive payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_types));
    FORY_RETURN_IF_ERROR(WriteFile(primitive_file, peer_bytes));
  }

  const char *collection_file = std::getenv("DATA_FILE_COLLECTION");
  if (collection_file != nullptr && collection_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(collection_file));
    FORY_TRY(peer_collections, fory.deserialize<collection::NumericCollections>(
                                   payload.data(), payload.size()));
    if (!(peer_collections == collections)) {
      return fory::Unexpected(
          fory::Error::invalid("peer collection payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_collections));
    FORY_RETURN_IF_ERROR(WriteFile(collection_file, peer_bytes));
  }

  const char *collection_union_file = std::getenv("DATA_FILE_COLLECTION_UNION");
  if (collection_union_file != nullptr && collection_union_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(collection_union_file));
    FORY_TRY(peer_union, fory.deserialize<collection::NumericCollectionUnion>(
                             payload.data(), payload.size()));
    if (!(peer_union == collection_union)) {
      return fory::Unexpected(
          fory::Error::invalid("peer collection union payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_union));
    FORY_RETURN_IF_ERROR(WriteFile(collection_union_file, peer_bytes));
  }

  const char *collection_array_file = std::getenv("DATA_FILE_COLLECTION_ARRAY");
  if (collection_array_file != nullptr && collection_array_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(collection_array_file));
    FORY_TRY(peer_array, fory.deserialize<collection::NumericCollectionsArray>(
                             payload.data(), payload.size()));
    if (!(peer_array == collections_array)) {
      return fory::Unexpected(fory::Error::invalid(
          "peer collection array payload mismatch: got " +
          DescribeCollectionArray(peer_array) + " expected " +
          DescribeCollectionArray(collections_array) + " local_meta=" +
          DescribeFieldTypes(
              fory.type_resolver()
                  .clone_struct_meta<collection::NumericCollectionsArray>())));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_array));
    FORY_RETURN_IF_ERROR(WriteFile(collection_array_file, peer_bytes));
  }

  const char *collection_array_union_file =
      std::getenv("DATA_FILE_COLLECTION_ARRAY_UNION");
  if (collection_array_union_file != nullptr &&
      collection_array_union_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(collection_array_union_file));
    FORY_TRY(peer_array_union,
             fory.deserialize<collection::NumericCollectionArrayUnion>(
                 payload.data(), payload.size()));
    if (!(peer_array_union == collection_array_union)) {
      return fory::Unexpected(
          fory::Error::invalid("peer collection array union payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_array_union));
    FORY_RETURN_IF_ERROR(WriteFile(collection_array_union_file, peer_bytes));
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

  const char *example_file = std::getenv("DATA_FILE_EXAMPLE");
  if (example_file != nullptr && example_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(example_file));
    FORY_TRY(peer_example, fory.deserialize<example_peer::ExampleMessage>(
                               payload.data(), payload.size()));
    if (!(peer_example == example_message)) {
      return fory::Unexpected(
          fory::Error::invalid("peer example payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_example));
    FORY_RETURN_IF_ERROR(WriteFile(example_file, peer_bytes));
  }

  const char *example_union_file = std::getenv("DATA_FILE_EXAMPLE_UNION");
  if (example_union_file != nullptr && example_union_file[0] != '\0') {
    FORY_TRY(payload, ReadFile(example_union_file));
    FORY_TRY(peer_example_union,
             fory.deserialize<example_peer::ExampleMessageUnion>(
                 payload.data(), payload.size()));
    if (!(peer_example_union == example_union)) {
      return fory::Unexpected(
          fory::Error::invalid("peer example union payload mismatch"));
    }
    FORY_TRY(peer_bytes, fory.serialize(peer_example_union));
    FORY_RETURN_IF_ERROR(WriteFile(example_union_file, peer_bytes));
  }

  auto ref_fory = fory::serialization::Fory::builder()
                      .xlang(true)
                      .compatible(compatible)
                      .check_struct_version(!compatible)
                      .track_ref(true)
                      .build();
  tree::register_types(ref_fory);
  graph::register_types(ref_fory);

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

bool ParseCompatibleMode(const char *value, bool *compatible) {
  if (value == nullptr || value[0] == '\0') {
    return false;
  }
  std::string normalized(value);
  for (char &ch : normalized) {
    ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
  }
  if (normalized == "1" || normalized == "true" || normalized == "yes") {
    *compatible = true;
    return true;
  }
  if (normalized == "0" || normalized == "false" || normalized == "no") {
    *compatible = false;
    return true;
  }
  return false;
}

} // namespace

int main() {
  const char *compat_env = std::getenv("IDL_COMPATIBLE");
  bool compatible = false;
  if (compat_env != nullptr && compat_env[0] != '\0') {
    if (!ParseCompatibleMode(compat_env, &compatible)) {
      std::cerr << "Unsupported IDL_COMPATIBLE value: " << compat_env
                << std::endl;
      return 1;
    }
    auto result = RunRoundTrip(compatible);
    if (!result.ok()) {
      std::cerr << "IDL roundtrip failed: " << result.error().message()
                << std::endl;
      return 1;
    }
    return 0;
  }

  auto result = RunRoundTrip(false);
  if (!result.ok()) {
    std::cerr << "IDL roundtrip failed: " << result.error().message()
              << std::endl;
    return 1;
  }
  result = RunRoundTrip(true);
  if (!result.ok()) {
    std::cerr << "IDL roundtrip failed: " << result.error().message()
              << std::endl;
    return 1;
  }
  return 0;
}
