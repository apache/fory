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

#include "fory/serialization/fory.h"
#include "gtest/gtest.h"
#include <cstdint>
#include <forward_list>
#include <map>
#include <memory>
#include <optional>
#include <vector>

namespace fory {
namespace serialization {

struct OptionalIntHolder {
  std::optional<int32_t> value;
  FORY_STRUCT(OptionalIntHolder, value);
};

struct OptionalSharedHolder {
  std::optional<std::shared_ptr<int32_t>> value;
  FORY_STRUCT(OptionalSharedHolder, value);
};

struct SharedPair {
  std::shared_ptr<int32_t> first;
  std::shared_ptr<int32_t> second;
  FORY_STRUCT(SharedPair, first, second);
};

struct UniqueHolder {
  std::unique_ptr<int32_t> value;
  FORY_STRUCT(UniqueHolder, value);
};

struct NonDefaultStruct {
  explicit NonDefaultStruct(int32_t value) : value(value) {}
  NonDefaultStruct() = delete;

  int32_t value;
  FORY_STRUCT(NonDefaultStruct, value);
};

struct CustomPolyLeading {
  virtual ~CustomPolyLeading() = default;
  int32_t leading_value = 0;
};

struct CustomPolyBase {
  virtual ~CustomPolyBase() = default;
  int32_t base_value = 0;
  FORY_STRUCT(CustomPolyBase, base_value);
};

struct CustomPolyDerived : CustomPolyLeading, CustomPolyBase {
  inline static int reads = 0;
  int32_t derived_value = 0;
};

template <> struct Serializer<CustomPolyDerived> {
  static constexpr TypeId type_id = TypeId::EXT;

  static void write(const CustomPolyDerived &value, WriteContext &ctx,
                    RefMode ref_mode, bool write_type,
                    bool has_generics = false) {
    (void)has_generics;
    write_not_null_ref_flag(ctx, ref_mode);
    if (write_type) {
      auto result =
          ctx.write_any_type_info(static_cast<uint32_t>(type_id),
                                  std::type_index(typeid(CustomPolyDerived)));
      if (!result.ok()) {
        ctx.set_error(std::move(result).error());
        return;
      }
    }
    write_data(value, ctx);
  }

  static void write_data(const CustomPolyDerived &value, WriteContext &ctx) {
    Serializer<int32_t>::write_data(value.leading_value, ctx);
    Serializer<int32_t>::write_data(value.base_value, ctx);
    Serializer<int32_t>::write_data(value.derived_value, ctx);
  }

  static void write_data_generic(const CustomPolyDerived &value,
                                 WriteContext &ctx, bool has_generics) {
    (void)has_generics;
    write_data(value, ctx);
  }

  static CustomPolyDerived read(ReadContext &ctx, RefMode ref_mode,
                                bool read_type) {
    bool has_value = read_null_only_flag(ctx, ref_mode);
    if (ctx.has_error() || !has_value) {
      return {};
    }
    if (read_type) {
      const TypeInfo *type_info = ctx.read_any_type_info(ctx.error());
      if (ctx.has_error()) {
        return {};
      }
      if (type_info == nullptr) {
        ctx.set_error(
            Error::type_error("TypeInfo for CustomPolyDerived not found"));
        return {};
      }
    }
    return read_data(ctx);
  }

  static CustomPolyDerived read_data(ReadContext &ctx) {
    ++CustomPolyDerived::reads;
    CustomPolyDerived value;
    value.leading_value = Serializer<int32_t>::read_data(ctx);
    value.base_value = Serializer<int32_t>::read_data(ctx);
    value.derived_value = Serializer<int32_t>::read_data(ctx);
    return value;
  }

  static CustomPolyDerived read_data_generic(ReadContext &ctx,
                                             bool has_generics) {
    (void)has_generics;
    return read_data(ctx);
  }

  static CustomPolyDerived read_with_type_info(ReadContext &ctx,
                                               RefMode ref_mode,
                                               const TypeInfo &type_info) {
    (void)type_info;
    return read(ctx, ref_mode, false);
  }
};

namespace {

Fory create_serializer(bool track_ref) {
  return Fory::builder()
      .xlang(true)
      .track_ref(track_ref)
      .compatible(true)
      .build();
}

// Helper to register all test struct types
inline void register_smart_ptr_test_types(Fory &fory) {
  uint32_t type_id = 100; // Start from 100 to avoid conflicts
  fory.register_struct<OptionalIntHolder>(type_id++);
  fory.register_struct<OptionalSharedHolder>(type_id++);
  fory.register_struct<SharedPair>(type_id++);
  fory.register_struct<UniqueHolder>(type_id++);
}

TEST(SmartPtrSerializerTest, OptionalIntRoundTrip) {
  OptionalIntHolder original;
  original.value = 42;

  auto fory = create_serializer(true);
  register_smart_ptr_test_types(fory);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<OptionalIntHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();
  ASSERT_TRUE(deserialized.value.has_value());
  EXPECT_EQ(*deserialized.value, 42);
}

TEST(SmartPtrSerializerTest, OptionalIntNullRoundTrip) {
  OptionalIntHolder original;
  original.value.reset();

  auto fory = create_serializer(true);
  register_smart_ptr_test_types(fory);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<OptionalIntHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();
  EXPECT_FALSE(deserialized.value.has_value());
}

TEST(SmartPtrSerializerTest, OptionalPreservesReferenceIds) {
  for (bool with_type_info : {false, true}) {
    SCOPED_TRACE(with_type_info);
    Config config;
    config.track_ref = true;
    ReadContext ctx(config, std::make_unique<TypeResolver>());
    Buffer buffer;

    buffer.write_int8(REF_VALUE_FLAG);
    buffer.write_var_int32(7);
    ctx.attach(buffer);

    TypeInfo type_info;
    std::optional<int32_t> optional;
    if (with_type_info) {
      optional = Serializer<std::optional<int32_t>>::read_with_type_info(
          ctx, RefMode::Tracking, type_info);
    } else {
      optional = Serializer<std::optional<int32_t>>::read(
          ctx, RefMode::Tracking, false);
    }
    ASSERT_FALSE(ctx.has_error()) << ctx.error().to_string();
    ASSERT_TRUE(optional.has_value());
    EXPECT_EQ(*optional, 7);
    EXPECT_TRUE(ctx.ref_reader().is_pending_ref(0));
    EXPECT_EQ(ctx.ref_reader().reserve_ref_id(), 1U);
    EXPECT_EQ(ctx.buffer().reader_index(), buffer.writer_index());
  }
}

TEST(SmartPtrSerializerTest, OptionalPreservesLaterAliases) {
  auto writer = create_serializer(true);
  auto reader = create_serializer(true);
  using WriterOptionals = std::vector<std::shared_ptr<int32_t>>;
  using ReaderOptionals = std::vector<std::optional<int32_t>>;
  using Owners = std::vector<std::shared_ptr<int32_t>>;
  using WriterRoot = std::tuple<WriterOptionals, Owners, int32_t>;
  using ReaderRoot = std::tuple<ReaderOptionals, Owners, int32_t>;
  auto owner = std::make_shared<int32_t>(17);
  WriterRoot original{
      {std::make_shared<int32_t>(7), std::make_shared<int32_t>(9)},
      {owner, owner},
      11};
  auto bytes = writer.serialize(original);
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();

  auto decoded = reader.deserialize<ReaderRoot>(*bytes);
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  const ReaderOptionals &values = std::get<0>(*decoded);
  ASSERT_EQ(values.size(), 2U);
  ASSERT_TRUE(values[0].has_value());
  ASSERT_TRUE(values[1].has_value());
  EXPECT_EQ(*values[0], 7);
  EXPECT_EQ(*values[1], 9);
  const Owners &owners = std::get<1>(*decoded);
  ASSERT_EQ(owners.size(), 2U);
  ASSERT_TRUE(owners[0]);
  EXPECT_EQ(*owners[0], 17);
  EXPECT_EQ(owners[0], owners[1]);
  EXPECT_EQ(std::get<2>(*decoded), 11);
}

TEST(SmartPtrSerializerTest, OptionalSharedPtrRoundTrip) {
  OptionalSharedHolder original;
  original.value = std::make_shared<int32_t>(42);

  auto fory = create_serializer(true);
  register_smart_ptr_test_types(fory);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<OptionalSharedHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  const auto &deserialized = deserialize_result.value();
  ASSERT_TRUE(deserialized.value.has_value());
  ASSERT_TRUE(deserialized.value.value());
  EXPECT_EQ(*deserialized.value.value(), 42);
}

TEST(SmartPtrSerializerTest, SharedPtrReferenceTracking) {
  auto shared = std::make_shared<int32_t>(1337);
  SharedPair original{shared, shared};

  auto fory = create_serializer(true);
  register_smart_ptr_test_types(fory);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result =
      fory.deserialize<SharedPair>(bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.first);
  ASSERT_TRUE(deserialized.second);
  EXPECT_EQ(*deserialized.first, 1337);
  EXPECT_EQ(*deserialized.second, 1337);
  EXPECT_EQ(deserialized.first, deserialized.second)
      << "Reference tracking should preserve shared_ptr aliasing";
}

TEST(SmartPtrSerializerTest, UniquePtrRoundTrip) {
  UniqueHolder original;
  original.value = std::make_unique<int32_t>(2025);

  auto fory = create_serializer(true);
  register_smart_ptr_test_types(fory);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<UniqueHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.value);
  EXPECT_EQ(*deserialized.value, 2025);
}

TEST(SmartPtrSerializerTest, UniquePtrNullRoundTrip) {
  UniqueHolder original;
  original.value.reset();

  auto fory = create_serializer(true);
  register_smart_ptr_test_types(fory);
  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<UniqueHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_EQ(deserialized.value, nullptr);
}

TEST(SmartPtrSerializerTest, RegistersNonDefaultStruct) {
  auto fory = create_serializer(true);
  ASSERT_TRUE(
      fory.register_struct<NonDefaultStruct>("test", "NonDefaultStruct").ok());

  NonDefaultStruct value(42);
  auto bytes = fory.serialize(value);
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
}

// ============================================================================
// Polymorphic type tests
// ============================================================================

struct Base {
  virtual ~Base() = default;
  virtual std::string get_type() const = 0;
  int32_t base_value = 0;
  FORY_STRUCT(Base, base_value);
};

struct Derived1 : Base {
  std::string get_type() const override { return "Derived1"; }
  std::string derived1_data;
  FORY_STRUCT(Derived1, FORY_BASE(Base), derived1_data);
};

struct Derived2 : Base {
  std::string get_type() const override { return "Derived2"; }
  int32_t derived2_data = 0;
  FORY_STRUCT(Derived2, FORY_BASE(Base), derived2_data);
};

struct Unrelated {
  inline static int constructions = 0;

  Unrelated() { ++constructions; }
  Unrelated(const Unrelated &other) : value(other.value) { ++constructions; }
  Unrelated(Unrelated &&other) noexcept : value(other.value) {
    ++constructions;
  }
  Unrelated &operator=(const Unrelated &) = default;
  Unrelated &operator=(Unrelated &&) = default;
  virtual ~Unrelated() = default;

  int32_t value = 0;
  FORY_STRUCT(Unrelated, value);
};

struct LeadingBase {
  virtual ~LeadingBase() = default;
  int32_t leading_value = 0;
  FORY_STRUCT(LeadingBase, leading_value);
};

struct IntermediateBase : Base {
  FORY_STRUCT(IntermediateBase, FORY_BASE(Base));
};

struct OffsetDerived : LeadingBase, IntermediateBase {
  std::string get_type() const override { return "OffsetDerived"; }
  int32_t derived_value = 0;
  FORY_STRUCT(OffsetDerived, FORY_BASE(LeadingBase),
              FORY_BASE(IntermediateBase), derived_value);
};

struct VirtualDerived : virtual Base {
  std::string get_type() const override { return "VirtualDerived"; }
  int32_t derived_value = 0;
  FORY_STRUCT(VirtualDerived, FORY_BASE(Base), derived_value);
};

struct VirtualData {
  int32_t data_value = 0;
  FORY_STRUCT(VirtualData, data_value);
};

struct MixedVirtualDerived : Base, virtual VirtualData {
  std::string get_type() const override { return "MixedVirtualDerived"; }
  int32_t derived_value = 0;
  FORY_STRUCT(MixedVirtualDerived, FORY_BASE(Base), FORY_BASE(VirtualData),
              derived_value);
};

struct UndeclaredDerived : LeadingBase, Base {
  std::string get_type() const override { return "UndeclaredDerived"; }
  int32_t derived_value = 0;
  FORY_STRUCT(UndeclaredDerived, derived_value);
};

struct PolymorphicSharedHolder {
  std::shared_ptr<Base> ptr;
  FORY_STRUCT(PolymorphicSharedHolder, ptr);
};

struct PolymorphicUniqueHolder {
  std::unique_ptr<Base> ptr;
  FORY_STRUCT(PolymorphicUniqueHolder, ptr);
};

void register_polymorphic_types(Fory &fory) {
  ASSERT_TRUE(fory.register_struct<Base>("test", "Base").ok());
  ASSERT_TRUE(fory.register_struct<Derived1>("test", "Derived1").ok());
  ASSERT_TRUE(fory.register_struct<Unrelated>("test", "Unrelated").ok());
  ASSERT_TRUE(
      fory.register_struct<OffsetDerived>("test", "OffsetDerived").ok());
  ASSERT_TRUE(
      fory.register_struct<VirtualDerived>("test", "VirtualDerived").ok());
  ASSERT_TRUE(
      fory.register_struct<MixedVirtualDerived>("test", "MixedVirtualDerived")
          .ok());
}

TEST(SmartPtrSerializerTest, RejectsUnrelatedSharedType) {
  for (bool compatible : {false, true}) {
    SCOPED_TRACE(compatible);
    auto fory = Fory::builder()
                    .xlang(true)
                    .track_ref(true)
                    .compatible(compatible)
                    .build();
    register_polymorphic_types(fory);

    auto unrelated = std::make_shared<Unrelated>();
    unrelated->value = 42;
    auto bytes_result = fory.serialize(unrelated);
    ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

    Unrelated::constructions = 0;
    auto rejected = fory.deserialize<std::shared_ptr<Base>>(
        bytes_result->data(), bytes_result->size());
    ASSERT_FALSE(rejected.ok());
    EXPECT_EQ(rejected.error().code(), ErrorCode::TypeError);
    EXPECT_NE(rejected.error().message().find("not compatible"),
              std::string::npos);
    EXPECT_EQ(Unrelated::constructions, 0);

    std::shared_ptr<Base> valid = std::make_shared<Derived1>();
    valid->base_value = 17;
    auto valid_bytes = fory.serialize(valid);
    ASSERT_TRUE(valid_bytes.ok()) << valid_bytes.error().to_string();
    auto decoded = fory.deserialize<std::shared_ptr<Base>>(valid_bytes->data(),
                                                           valid_bytes->size());
    ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
    ASSERT_NE(decoded.value(), nullptr);
    EXPECT_EQ(decoded.value()->get_type(), "Derived1");
    EXPECT_EQ(decoded.value()->base_value, 17);
  }
}

TEST(SmartPtrSerializerTest, RejectsUnrelatedUniqueType) {
  for (bool compatible : {false, true}) {
    SCOPED_TRACE(compatible);
    auto fory = Fory::builder()
                    .xlang(true)
                    .track_ref(true)
                    .compatible(compatible)
                    .build();
    register_polymorphic_types(fory);

    auto unrelated = std::make_unique<Unrelated>();
    unrelated->value = 42;
    auto bytes_result = fory.serialize(unrelated);
    ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

    Unrelated::constructions = 0;
    auto rejected = fory.deserialize<std::unique_ptr<Base>>(
        bytes_result->data(), bytes_result->size());
    ASSERT_FALSE(rejected.ok());
    EXPECT_EQ(rejected.error().code(), ErrorCode::TypeError);
    EXPECT_NE(rejected.error().message().find("not compatible"),
              std::string::npos);
    EXPECT_EQ(Unrelated::constructions, 0);
  }
}

TEST(SmartPtrSerializerTest, RejectsUnrelatedCollections) {
  for (bool compatible : {false, true}) {
    for (bool track_ref : {false, true}) {
      SCOPED_TRACE(::testing::Message() << "compatible=" << compatible
                                        << ", track_ref=" << track_ref);
      auto fory = Fory::builder()
                      .xlang(true)
                      .track_ref(track_ref)
                      .compatible(compatible)
                      .build();
      register_polymorphic_types(fory);

      std::vector<std::shared_ptr<Unrelated>> values{
          std::make_shared<Unrelated>()};
      auto bytes_result = fory.serialize(values);
      ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

      Unrelated::constructions = 0;
      auto rejected = fory.deserialize<std::vector<std::shared_ptr<Base>>>(
          bytes_result->data(), bytes_result->size());
      ASSERT_FALSE(rejected.ok());
      EXPECT_EQ(rejected.error().code(), ErrorCode::TypeError);
      EXPECT_EQ(Unrelated::constructions, 0);
    }

    auto fory = Fory::builder()
                    .xlang(true)
                    .track_ref(true)
                    .compatible(compatible)
                    .build();
    register_polymorphic_types(fory);
    std::vector<std::unique_ptr<Unrelated>> values;
    values.emplace_back(std::make_unique<Unrelated>());
    auto bytes_result = fory.serialize(values);
    ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

    Unrelated::constructions = 0;
    auto rejected = fory.deserialize<std::vector<std::unique_ptr<Base>>>(
        bytes_result->data(), bytes_result->size());
    ASSERT_FALSE(rejected.ok());
    EXPECT_EQ(rejected.error().code(), ErrorCode::TypeError);
    EXPECT_EQ(Unrelated::constructions, 0);
  }
}

TEST(SmartPtrSerializerTest, AdjustsMultipleInheritance) {
  for (bool compatible : {false, true}) {
    SCOPED_TRACE(compatible);
    auto fory = Fory::builder()
                    .xlang(true)
                    .track_ref(true)
                    .compatible(compatible)
                    .build();
    register_polymorphic_types(fory);

    auto concrete = std::make_shared<OffsetDerived>();
    concrete->leading_value = 11;
    concrete->base_value = 22;
    concrete->derived_value = 33;
    std::shared_ptr<Base> shared = concrete;
    ASSERT_NE(static_cast<const void *>(concrete.get()),
              static_cast<const void *>(shared.get()));

    auto shared_bytes = fory.serialize(shared);
    ASSERT_TRUE(shared_bytes.ok()) << shared_bytes.error().to_string();
    auto shared_result = fory.deserialize<std::shared_ptr<Base>>(
        shared_bytes->data(), shared_bytes->size());
    ASSERT_TRUE(shared_result.ok()) << shared_result.error().to_string();
    auto *shared_derived =
        dynamic_cast<OffsetDerived *>(shared_result.value().get());
    ASSERT_NE(shared_derived, nullptr);
    EXPECT_EQ(shared_derived->leading_value, 11);
    EXPECT_EQ(shared_derived->base_value, 22);
    EXPECT_EQ(shared_derived->derived_value, 33);

    auto unique_concrete = std::make_unique<OffsetDerived>();
    unique_concrete->leading_value = 44;
    unique_concrete->base_value = 55;
    unique_concrete->derived_value = 66;
    std::unique_ptr<Base> unique = std::move(unique_concrete);
    auto unique_bytes = fory.serialize(unique);
    ASSERT_TRUE(unique_bytes.ok()) << unique_bytes.error().to_string();
    auto unique_result = fory.deserialize<std::unique_ptr<Base>>(
        unique_bytes->data(), unique_bytes->size());
    ASSERT_TRUE(unique_result.ok()) << unique_result.error().to_string();
    auto *unique_derived =
        dynamic_cast<OffsetDerived *>(unique_result.value().get());
    ASSERT_NE(unique_derived, nullptr);
    EXPECT_EQ(unique_derived->leading_value, 44);
    EXPECT_EQ(unique_derived->base_value, 55);
    EXPECT_EQ(unique_derived->derived_value, 66);

    std::vector<std::shared_ptr<Base>> shared_values;
    auto shared_value = std::make_shared<OffsetDerived>();
    shared_value->leading_value = 71;
    shared_value->base_value = 72;
    shared_value->derived_value = 73;
    shared_values.push_back(std::move(shared_value));
    auto shared_vector_bytes = fory.serialize(shared_values);
    ASSERT_TRUE(shared_vector_bytes.ok())
        << shared_vector_bytes.error().to_string();
    auto shared_vector_result =
        fory.deserialize<std::vector<std::shared_ptr<Base>>>(
            shared_vector_bytes->data(), shared_vector_bytes->size());
    ASSERT_TRUE(shared_vector_result.ok())
        << shared_vector_result.error().to_string();
    ASSERT_EQ(shared_vector_result->size(), 1u);
    auto *shared_vector_derived =
        dynamic_cast<OffsetDerived *>(shared_vector_result->front().get());
    ASSERT_NE(shared_vector_derived, nullptr);
    EXPECT_EQ(shared_vector_derived->leading_value, 71);
    EXPECT_EQ(shared_vector_derived->base_value, 72);
    EXPECT_EQ(shared_vector_derived->derived_value, 73);

    std::vector<std::unique_ptr<Base>> unique_values;
    auto unique_value = std::make_unique<OffsetDerived>();
    unique_value->leading_value = 81;
    unique_value->base_value = 82;
    unique_value->derived_value = 83;
    unique_values.push_back(std::move(unique_value));
    auto unique_vector_bytes = fory.serialize(unique_values);
    ASSERT_TRUE(unique_vector_bytes.ok())
        << unique_vector_bytes.error().to_string();
    auto unique_vector_result =
        fory.deserialize<std::vector<std::unique_ptr<Base>>>(
            unique_vector_bytes->data(), unique_vector_bytes->size());
    ASSERT_TRUE(unique_vector_result.ok())
        << unique_vector_result.error().to_string();
    ASSERT_EQ(unique_vector_result->size(), 1u);
    auto *unique_vector_derived =
        dynamic_cast<OffsetDerived *>(unique_vector_result->front().get());
    ASSERT_NE(unique_vector_derived, nullptr);
    EXPECT_EQ(unique_vector_derived->leading_value, 81);
    EXPECT_EQ(unique_vector_derived->base_value, 82);
    EXPECT_EQ(unique_vector_derived->derived_value, 83);

    std::map<int32_t, std::shared_ptr<Base>> shared_map;
    auto map_value = std::make_shared<OffsetDerived>();
    map_value->leading_value = 91;
    map_value->base_value = 92;
    map_value->derived_value = 93;
    shared_map.emplace(1, std::move(map_value));
    auto shared_map_bytes = fory.serialize(shared_map);
    ASSERT_TRUE(shared_map_bytes.ok()) << shared_map_bytes.error().to_string();
    auto shared_map_result =
        fory.deserialize<std::map<int32_t, std::shared_ptr<Base>>>(
            shared_map_bytes->data(), shared_map_bytes->size());
    ASSERT_TRUE(shared_map_result.ok())
        << shared_map_result.error().to_string();
    ASSERT_EQ(shared_map_result->size(), 1u);
    auto *shared_map_derived =
        dynamic_cast<OffsetDerived *>(shared_map_result->begin()->second.get());
    ASSERT_NE(shared_map_derived, nullptr);
    EXPECT_EQ(shared_map_derived->leading_value, 91);
    EXPECT_EQ(shared_map_derived->base_value, 92);
    EXPECT_EQ(shared_map_derived->derived_value, 93);
  }
}

TEST(SmartPtrSerializerTest, AdjustsVirtualInheritance) {
  for (bool compatible : {false, true}) {
    SCOPED_TRACE(compatible);
    auto fory = Fory::builder()
                    .xlang(true)
                    .track_ref(true)
                    .compatible(compatible)
                    .build();
    register_polymorphic_types(fory);

    std::vector<std::shared_ptr<Base>> values;
    auto value = std::make_shared<VirtualDerived>();
    value->base_value = 101;
    value->derived_value = 202;
    values.push_back(std::move(value));

    auto bytes = fory.serialize(values);
    ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
    auto result = fory.deserialize<std::vector<std::shared_ptr<Base>>>(
        bytes->data(), bytes->size());
    ASSERT_TRUE(result.ok()) << result.error().to_string();
    ASSERT_EQ(result->size(), 1u);
    auto *derived = dynamic_cast<VirtualDerived *>(result->front().get());
    ASSERT_NE(derived, nullptr);
    EXPECT_EQ(derived->base_value, 101);
    EXPECT_EQ(derived->derived_value, 202);

    std::vector<std::shared_ptr<Base>> mixed_values;
    auto mixed_value = std::make_shared<MixedVirtualDerived>();
    mixed_value->base_value = 303;
    mixed_value->data_value = 404;
    mixed_value->derived_value = 505;
    mixed_values.push_back(std::move(mixed_value));

    auto mixed_bytes = fory.serialize(mixed_values);
    ASSERT_TRUE(mixed_bytes.ok()) << mixed_bytes.error().to_string();
    auto mixed_result = fory.deserialize<std::vector<std::shared_ptr<Base>>>(
        mixed_bytes->data(), mixed_bytes->size());
    ASSERT_TRUE(mixed_result.ok()) << mixed_result.error().to_string();
    ASSERT_EQ(mixed_result->size(), 1u);
    auto *mixed_derived =
        dynamic_cast<MixedVirtualDerived *>(mixed_result->front().get());
    ASSERT_NE(mixed_derived, nullptr);
    EXPECT_EQ(mixed_derived->base_value, 303);
    EXPECT_EQ(mixed_derived->data_value, 404);
    EXPECT_EQ(mixed_derived->derived_value, 505);
  }
}

TEST(SmartPtrSerializerTest, UndeclaredBaseWrite) {
  for (bool compatible : {false, true}) {
    SCOPED_TRACE(compatible);
    auto fory = Fory::builder()
                    .xlang(true)
                    .track_ref(true)
                    .compatible(compatible)
                    .build();
    register_polymorphic_types(fory);
    ASSERT_TRUE(
        fory.register_struct<UndeclaredDerived>("test", "UndeclaredDerived")
            .ok());

    auto concrete = std::make_shared<UndeclaredDerived>();
    concrete->derived_value = 123;
    std::shared_ptr<Base> value = concrete;
    ASSERT_NE(static_cast<const void *>(concrete.get()),
              static_cast<const void *>(value.get()));

    auto bytes = fory.serialize(value);
    ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
    auto exact = fory.deserialize<std::shared_ptr<UndeclaredDerived>>(
        bytes->data(), bytes->size());
    ASSERT_TRUE(exact.ok()) << exact.error().to_string();
    EXPECT_EQ(exact.value()->derived_value, 123);

    auto rejected =
        fory.deserialize<std::shared_ptr<Base>>(bytes->data(), bytes->size());
    ASSERT_FALSE(rejected.ok());
    EXPECT_EQ(rejected.error().code(), ErrorCode::TypeError);
  }
}

TEST(SmartPtrSerializerTest, CustomPolymorphicExactRead) {
  for (bool compatible : {false, true}) {
    SCOPED_TRACE(compatible);
    auto fory = Fory::builder()
                    .xlang(true)
                    .track_ref(true)
                    .compatible(compatible)
                    .build();
    ASSERT_TRUE(fory.register_extension_type<CustomPolyDerived>(
                        "test", "CustomPolyDerived")
                    .ok());

    auto concrete = std::make_shared<CustomPolyDerived>();
    concrete->leading_value = 11;
    concrete->base_value = 22;
    concrete->derived_value = 33;
    std::shared_ptr<CustomPolyBase> value = concrete;
    ASSERT_NE(static_cast<const void *>(concrete.get()),
              static_cast<const void *>(value.get()));

    auto bytes = fory.serialize(value);
    ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
    CustomPolyDerived::reads = 0;
    auto result = fory.deserialize<std::shared_ptr<CustomPolyDerived>>(
        bytes->data(), bytes->size());
    ASSERT_TRUE(result.ok()) << result.error().to_string();
    EXPECT_EQ(CustomPolyDerived::reads, 1);
    EXPECT_EQ(result.value()->leading_value, 11);
    EXPECT_EQ(result.value()->base_value, 22);
    EXPECT_EQ(result.value()->derived_value, 33);

    CustomPolyDerived::reads = 0;
    auto rejected = fory.deserialize<std::shared_ptr<CustomPolyBase>>(
        bytes->data(), bytes->size());
    ASSERT_FALSE(rejected.ok());
    EXPECT_EQ(rejected.error().code(), ErrorCode::TypeError);
    EXPECT_EQ(CustomPolyDerived::reads, 0);
  }
}

TEST(SmartPtrSerializerTest, ForwardListPolymorphism) {
  for (bool compatible : {false, true}) {
    SCOPED_TRACE(compatible);
    auto fory = Fory::builder()
                    .xlang(true)
                    .track_ref(true)
                    .compatible(compatible)
                    .build();
    register_polymorphic_types(fory);

    auto shared_value = std::make_shared<OffsetDerived>();
    shared_value->leading_value = 601;
    shared_value->base_value = 602;
    shared_value->derived_value = 603;
    std::forward_list<std::shared_ptr<Base>> shared_values{shared_value,
                                                           shared_value};

    auto shared_bytes = fory.serialize(shared_values);
    ASSERT_TRUE(shared_bytes.ok()) << shared_bytes.error().to_string();
    auto shared_result =
        fory.deserialize<std::forward_list<std::shared_ptr<Base>>>(
            shared_bytes->data(), shared_bytes->size());
    ASSERT_TRUE(shared_result.ok()) << shared_result.error().to_string();
    auto shared_it = shared_result->begin();
    ASSERT_NE(shared_it, shared_result->end());
    auto *shared_derived = dynamic_cast<OffsetDerived *>(shared_it->get());
    ASSERT_NE(shared_derived, nullptr);
    EXPECT_EQ(shared_derived->leading_value, 601);
    EXPECT_EQ(shared_derived->base_value, 602);
    EXPECT_EQ(shared_derived->derived_value, 603);
    auto first_ptr = shared_it->get();
    ++shared_it;
    ASSERT_NE(shared_it, shared_result->end());
    EXPECT_EQ(first_ptr, shared_it->get());

    std::forward_list<std::unique_ptr<Base>> unique_values;
    auto unique_value = std::make_unique<OffsetDerived>();
    unique_value->leading_value = 701;
    unique_value->base_value = 702;
    unique_value->derived_value = 703;
    unique_values.push_front(std::move(unique_value));

    auto unique_bytes = fory.serialize(unique_values);
    ASSERT_TRUE(unique_bytes.ok()) << unique_bytes.error().to_string();
    auto unique_result =
        fory.deserialize<std::forward_list<std::unique_ptr<Base>>>(
            unique_bytes->data(), unique_bytes->size());
    ASSERT_TRUE(unique_result.ok()) << unique_result.error().to_string();
    ASSERT_NE(unique_result->begin(), unique_result->end());
    auto *unique_derived =
        dynamic_cast<OffsetDerived *>(unique_result->front().get());
    ASSERT_NE(unique_derived, nullptr);
    EXPECT_EQ(unique_derived->leading_value, 701);
    EXPECT_EQ(unique_derived->base_value, 702);
    EXPECT_EQ(unique_derived->derived_value, 703);
  }
}

TEST(SmartPtrSerializerTest, PolymorphicSharedPtrDerived1) {
  auto fory = create_serializer(true);
  fory.register_struct<PolymorphicSharedHolder>(200);
  fory.register_struct<Base>("test", "Base");
  auto register_result = fory.register_struct<Derived1>("test", "Derived1");
  ASSERT_TRUE(register_result.ok())
      << "Failed to register Derived1: " << register_result.error().to_string();

  PolymorphicSharedHolder original;
  original.ptr = std::make_shared<Derived1>();
  original.ptr->base_value = 42;
  static_cast<Derived1 *>(original.ptr.get())->derived1_data = "hello";

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<PolymorphicSharedHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->get_type(), "Derived1");
  EXPECT_EQ(deserialized.ptr->base_value, 42);
  EXPECT_EQ(static_cast<Derived1 *>(deserialized.ptr.get())->derived1_data,
            "hello");
}

TEST(SmartPtrSerializerTest, PolymorphicSharedPtrDerived2) {
  auto fory = create_serializer(true);
  fory.register_struct<PolymorphicSharedHolder>(200);
  fory.register_struct<Base>("test", "Base");
  auto register_result = fory.register_struct<Derived2>("test", "Derived2");
  ASSERT_TRUE(register_result.ok())
      << "Failed to register Derived2: " << register_result.error().to_string();

  PolymorphicSharedHolder original;
  original.ptr = std::make_shared<Derived2>();
  original.ptr->base_value = 99;
  static_cast<Derived2 *>(original.ptr.get())->derived2_data = 1234;

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<PolymorphicSharedHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->get_type(), "Derived2");
  EXPECT_EQ(deserialized.ptr->base_value, 99);
  EXPECT_EQ(static_cast<Derived2 *>(deserialized.ptr.get())->derived2_data,
            1234);
}

TEST(SmartPtrSerializerTest, PolymorphicUniquePtrDerived1) {
  auto fory = create_serializer(true);
  fory.register_struct<PolymorphicUniqueHolder>(201);
  fory.register_struct<Base>("test", "Base");
  auto register_result = fory.register_struct<Derived1>("test", "Derived1");
  ASSERT_TRUE(register_result.ok())
      << "Failed to register Derived1: " << register_result.error().to_string();

  PolymorphicUniqueHolder original;
  original.ptr = std::make_unique<Derived1>();
  original.ptr->base_value = 42;
  static_cast<Derived1 *>(original.ptr.get())->derived1_data = "world";

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<PolymorphicUniqueHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->get_type(), "Derived1");
  EXPECT_EQ(deserialized.ptr->base_value, 42);
  EXPECT_EQ(static_cast<Derived1 *>(deserialized.ptr.get())->derived1_data,
            "world");
}

TEST(SmartPtrSerializerTest, PolymorphicUniquePtrDerived2) {
  auto fory = create_serializer(true);
  fory.register_struct<PolymorphicUniqueHolder>(201);
  fory.register_struct<Base>("test", "Base");
  auto register_result = fory.register_struct<Derived2>("test", "Derived2");
  ASSERT_TRUE(register_result.ok())
      << "Failed to register Derived2: " << register_result.error().to_string();

  PolymorphicUniqueHolder original;
  original.ptr = std::make_unique<Derived2>();
  original.ptr->base_value = 77;
  static_cast<Derived2 *>(original.ptr.get())->derived2_data = 5678;

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<PolymorphicUniqueHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->get_type(), "Derived2");
  EXPECT_EQ(deserialized.ptr->base_value, 77);
  EXPECT_EQ(static_cast<Derived2 *>(deserialized.ptr.get())->derived2_data,
            5678);
}

// ============================================================================
// Max Dynamic Depth Tests
// ============================================================================

// Container struct for testing nested polymorphic depth limits
struct NestedContainer {
  virtual ~NestedContainer() = default;
  int32_t value = 0;
  std::shared_ptr<NestedContainer> nested;
  FORY_STRUCT(NestedContainer, value, nested);
};

// Holder struct to wrap nested container in a polymorphic shared_ptr
struct NestedContainerHolder {
  std::shared_ptr<NestedContainer> ptr;
  FORY_STRUCT(NestedContainerHolder, ptr);
};

struct UniqueNestedContainer {
  UniqueNestedContainer() = default;
  UniqueNestedContainer(const UniqueNestedContainer &) = delete;
  UniqueNestedContainer &operator=(const UniqueNestedContainer &) = delete;
  UniqueNestedContainer(UniqueNestedContainer &&) noexcept = default;
  UniqueNestedContainer &operator=(UniqueNestedContainer &&) noexcept = default;
  virtual ~UniqueNestedContainer() = default;
  std::unique_ptr<UniqueNestedContainer> nested;
  FORY_STRUCT(UniqueNestedContainer, nested);
};

struct StaticSharedNode {
  int32_t value = 0;
  std::shared_ptr<StaticSharedNode> nested;
  FORY_STRUCT(StaticSharedNode, value, nested);
};

struct StaticSharedHolder {
  std::shared_ptr<StaticSharedNode> ptr;
  FORY_STRUCT(StaticSharedHolder, ptr);
};

struct StaticUniqueNode {
  int32_t value = 0;
  std::unique_ptr<StaticUniqueNode> nested;
  FORY_STRUCT(StaticUniqueNode, value, nested);
};

struct StaticUniqueHolder {
  std::unique_ptr<StaticUniqueNode> ptr;
  FORY_STRUCT(StaticUniqueHolder, ptr);
};

TEST(SmartPtrSerializerTest, MaxDynDepthExceeded) {
  // Create Fory with max_dyn_depth=2
  auto fory =
      Fory::builder().xlang(true).compatible(false).max_dyn_depth(2).build();
  fory.register_struct<NestedContainerHolder>(300);
  fory.register_struct<NestedContainer>("test", "NestedContainer");

  // Create 3 levels of nesting (exceeds max_dyn_depth=2)
  auto level3 = std::make_shared<NestedContainer>();
  level3->value = 3;
  level3->nested = nullptr;

  auto level2 = std::make_shared<NestedContainer>();
  level2->value = 2;
  level2->nested = level3;

  auto level1 = std::make_shared<NestedContainer>();
  level1->value = 1;
  level1->nested = level2;

  NestedContainerHolder holder;
  holder.ptr = level1;

  // Serialize should succeed
  auto bytes_result = fory.serialize(holder);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  // Deserialize should fail due to max depth exceeded
  auto deserialize_result = fory.deserialize<NestedContainerHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_FALSE(deserialize_result.ok())
      << "Expected deserialization to fail due to max depth";

  // Check error message mentions depth
  std::string error_msg = deserialize_result.error().to_string();
  EXPECT_TRUE(error_msg.find("depth") != std::string::npos ||
              error_msg.find("Depth") != std::string::npos)
      << "Error should mention depth: " << error_msg;
}

TEST(SmartPtrSerializerTest, SharedCollectionDepth) {
  auto writer = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(10)
                    .build();
  auto reader = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(1)
                    .build();
  ASSERT_TRUE(
      writer.register_struct<NestedContainer>("test", "NestedContainer").ok());
  ASSERT_TRUE(
      reader.register_struct<NestedContainer>("test", "NestedContainer").ok());

  auto root = std::make_shared<NestedContainer>();
  root->nested = std::make_shared<NestedContainer>();
  std::vector<std::shared_ptr<NestedContainer>> deep;
  deep.push_back(std::move(root));

  auto deep_bytes = writer.serialize(deep);
  ASSERT_TRUE(deep_bytes.ok()) << deep_bytes.error().to_string();
  auto rejected =
      reader.deserialize<std::vector<std::shared_ptr<NestedContainer>>>(
          deep_bytes->data(), deep_bytes->size());
  ASSERT_FALSE(rejected.ok());
  EXPECT_EQ(rejected.error().code(), ErrorCode::DepthExceed);

  std::vector<std::shared_ptr<NestedContainer>> shallow;
  shallow.push_back(std::make_shared<NestedContainer>());
  auto shallow_bytes = writer.serialize(shallow);
  ASSERT_TRUE(shallow_bytes.ok()) << shallow_bytes.error().to_string();
  auto decoded =
      reader.deserialize<std::vector<std::shared_ptr<NestedContainer>>>(
          shallow_bytes->data(), shallow_bytes->size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  ASSERT_EQ(decoded->size(), 1U);
  ASSERT_NE(decoded->front(), nullptr);
}

TEST(SmartPtrSerializerTest, StaticSharedDepth) {
  auto writer = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(10)
                    .build();
  auto reader = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(1)
                    .build();
  ASSERT_TRUE(writer.register_struct<StaticSharedHolder>(310).ok());
  ASSERT_TRUE(reader.register_struct<StaticSharedHolder>(310).ok());
  ASSERT_TRUE(
      writer.register_struct<StaticSharedNode>("test", "StaticSharedNode")
          .ok());
  ASSERT_TRUE(
      reader.register_struct<StaticSharedNode>("test", "StaticSharedNode")
          .ok());

  StaticSharedHolder deep;
  deep.ptr = std::make_shared<StaticSharedNode>();
  deep.ptr->nested = std::make_shared<StaticSharedNode>();
  auto deep_bytes = writer.serialize(deep);
  ASSERT_TRUE(deep_bytes.ok()) << deep_bytes.error().to_string();
  auto rejected = reader.deserialize<StaticSharedHolder>(deep_bytes->data(),
                                                         deep_bytes->size());
  ASSERT_FALSE(rejected.ok());
  EXPECT_EQ(rejected.error().code(), ErrorCode::DepthExceed);

  StaticSharedHolder shallow;
  shallow.ptr = std::make_shared<StaticSharedNode>();
  shallow.ptr->value = 7;
  auto shallow_bytes = writer.serialize(shallow);
  ASSERT_TRUE(shallow_bytes.ok()) << shallow_bytes.error().to_string();
  auto decoded = reader.deserialize<StaticSharedHolder>(shallow_bytes->data(),
                                                        shallow_bytes->size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  ASSERT_NE(decoded->ptr, nullptr);
  EXPECT_EQ(decoded->ptr->value, 7);
}

TEST(SmartPtrSerializerTest, UniqueCollectionDepth) {
  auto writer = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(10)
                    .build();
  auto reader = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(1)
                    .build();
  ASSERT_TRUE(writer
                  .register_struct<UniqueNestedContainer>(
                      "test", "UniqueNestedContainer")
                  .ok());
  ASSERT_TRUE(reader
                  .register_struct<UniqueNestedContainer>(
                      "test", "UniqueNestedContainer")
                  .ok());

  auto root = std::make_unique<UniqueNestedContainer>();
  root->nested = std::make_unique<UniqueNestedContainer>();
  std::vector<std::unique_ptr<UniqueNestedContainer>> deep;
  deep.push_back(std::move(root));

  auto deep_bytes = writer.serialize(deep);
  ASSERT_TRUE(deep_bytes.ok()) << deep_bytes.error().to_string();
  auto rejected =
      reader.deserialize<std::vector<std::unique_ptr<UniqueNestedContainer>>>(
          deep_bytes->data(), deep_bytes->size());
  ASSERT_FALSE(rejected.ok());
  EXPECT_EQ(rejected.error().code(), ErrorCode::DepthExceed);

  std::vector<std::unique_ptr<UniqueNestedContainer>> shallow;
  shallow.push_back(std::make_unique<UniqueNestedContainer>());
  auto shallow_bytes = writer.serialize(shallow);
  ASSERT_TRUE(shallow_bytes.ok()) << shallow_bytes.error().to_string();
  auto decoded =
      reader.deserialize<std::vector<std::unique_ptr<UniqueNestedContainer>>>(
          shallow_bytes->data(), shallow_bytes->size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  ASSERT_EQ(decoded->size(), 1U);
  ASSERT_NE(decoded->front(), nullptr);
}

TEST(SmartPtrSerializerTest, StaticUniqueDepth) {
  auto writer = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(10)
                    .build();
  auto reader = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(1)
                    .build();
  ASSERT_TRUE(writer.register_struct<StaticUniqueHolder>(311).ok());
  ASSERT_TRUE(reader.register_struct<StaticUniqueHolder>(311).ok());
  ASSERT_TRUE(
      writer.register_struct<StaticUniqueNode>("test", "StaticUniqueNode")
          .ok());
  ASSERT_TRUE(
      reader.register_struct<StaticUniqueNode>("test", "StaticUniqueNode")
          .ok());

  StaticUniqueHolder deep;
  deep.ptr = std::make_unique<StaticUniqueNode>();
  deep.ptr->nested = std::make_unique<StaticUniqueNode>();
  auto deep_bytes = writer.serialize(deep);
  ASSERT_TRUE(deep_bytes.ok()) << deep_bytes.error().to_string();
  auto rejected = reader.deserialize<StaticUniqueHolder>(deep_bytes->data(),
                                                         deep_bytes->size());
  ASSERT_FALSE(rejected.ok());
  EXPECT_EQ(rejected.error().code(), ErrorCode::DepthExceed);

  StaticUniqueHolder shallow;
  shallow.ptr = std::make_unique<StaticUniqueNode>();
  shallow.ptr->value = 9;
  auto shallow_bytes = writer.serialize(shallow);
  ASSERT_TRUE(shallow_bytes.ok()) << shallow_bytes.error().to_string();
  auto decoded = reader.deserialize<StaticUniqueHolder>(shallow_bytes->data(),
                                                        shallow_bytes->size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  ASSERT_NE(decoded->ptr, nullptr);
  EXPECT_EQ(decoded->ptr->value, 9);
}

TEST(SmartPtrSerializerTest, MaxDynDepthSufficient) {
  // Create Fory with max_dyn_depth=5 (sufficient for 3 levels)
  auto fory =
      Fory::builder().xlang(true).compatible(false).max_dyn_depth(5).build();
  fory.register_struct<NestedContainerHolder>(300);
  fory.register_struct<NestedContainer>("test", "NestedContainer");

  // Create 3 levels of nesting
  auto level3 = std::make_shared<NestedContainer>();
  level3->value = 3;
  level3->nested = nullptr;

  auto level2 = std::make_shared<NestedContainer>();
  level2->value = 2;
  level2->nested = level3;

  auto level1 = std::make_shared<NestedContainer>();
  level1->value = 1;
  level1->nested = level2;

  NestedContainerHolder holder;
  holder.ptr = level1;

  // Serialize should succeed
  auto bytes_result = fory.serialize(holder);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  // Deserialize should succeed with sufficient depth
  auto deserialize_result = fory.deserialize<NestedContainerHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << "Deserialization failed: " << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->value, 1);
  ASSERT_TRUE(deserialized.ptr->nested);
  EXPECT_EQ(deserialized.ptr->nested->value, 2);
  ASSERT_TRUE(deserialized.ptr->nested->nested);
  EXPECT_EQ(deserialized.ptr->nested->nested->value, 3);
  EXPECT_FALSE(deserialized.ptr->nested->nested->nested);
}

TEST(SmartPtrSerializerTest, MaxDynDepthDefault) {
  // Default max_dyn_depth is 5
  auto fory = Fory::builder().xlang(true).compatible(false).build();
  EXPECT_EQ(fory.config().max_dyn_depth, 5);
}

} // namespace

// ============================================================================
// Monomorphic field tests
// ============================================================================
namespace {

// A polymorphic base class (has virtual methods)
struct PolymorphicBaseForMono {
  virtual ~PolymorphicBaseForMono() = default;
  virtual std::string name() const { return "PolymorphicBaseForMono"; }
  int32_t value = 0;
  std::string data;
  FORY_STRUCT(PolymorphicBaseForMono, value, data);
};

struct MonoSharedNode {
  virtual ~MonoSharedNode() = default;
  std::shared_ptr<MonoSharedNode> nested;
  FORY_STRUCT(MonoSharedNode, (nested, fory::F().nullable().dynamic(false)));
};

struct MonoSharedHolder {
  std::shared_ptr<MonoSharedNode> ptr;
  FORY_STRUCT(MonoSharedHolder, (ptr, fory::F().nullable().dynamic(false)));
};

struct MonoUniqueNode {
  MonoUniqueNode() = default;
  MonoUniqueNode(const MonoUniqueNode &) = delete;
  MonoUniqueNode &operator=(const MonoUniqueNode &) = delete;
  MonoUniqueNode(MonoUniqueNode &&) noexcept = default;
  MonoUniqueNode &operator=(MonoUniqueNode &&) noexcept = default;
  virtual ~MonoUniqueNode() = default;
  std::unique_ptr<MonoUniqueNode> nested;
  FORY_STRUCT(MonoUniqueNode, (nested, fory::F().nullable().dynamic(false)));
};

struct MonoUniqueHolder {
  std::unique_ptr<MonoUniqueNode> ptr;
  FORY_STRUCT(MonoUniqueHolder, (ptr, fory::F().nullable().dynamic(false)));
};

struct NonDynamicFieldHolder {
  std::shared_ptr<PolymorphicBaseForMono> ptr;
  FORY_STRUCT(NonDynamicFieldHolder,
              (ptr, fory::F().nullable().dynamic(false)));
};

TEST(SmartPtrSerializerTest, StaticPolymorphicDepth) {
  auto writer = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(10)
                    .build();
  auto reader = Fory::builder()
                    .xlang(true)
                    .track_ref(false)
                    .compatible(false)
                    .max_dyn_depth(1)
                    .build();
  ASSERT_TRUE(writer.register_struct<MonoSharedHolder>(320).ok());
  ASSERT_TRUE(reader.register_struct<MonoSharedHolder>(320).ok());
  ASSERT_TRUE(writer.register_struct<MonoSharedNode>(321).ok());
  ASSERT_TRUE(reader.register_struct<MonoSharedNode>(321).ok());
  ASSERT_TRUE(writer.register_struct<MonoUniqueHolder>(322).ok());
  ASSERT_TRUE(reader.register_struct<MonoUniqueHolder>(322).ok());
  ASSERT_TRUE(writer.register_struct<MonoUniqueNode>(323).ok());
  ASSERT_TRUE(reader.register_struct<MonoUniqueNode>(323).ok());

  MonoSharedHolder shared;
  shared.ptr = std::make_shared<MonoSharedNode>();
  shared.ptr->nested = std::make_shared<MonoSharedNode>();
  auto shared_bytes = writer.serialize(shared);
  ASSERT_TRUE(shared_bytes.ok()) << shared_bytes.error().to_string();
  auto shared_rejected = reader.deserialize<MonoSharedHolder>(
      shared_bytes->data(), shared_bytes->size());
  ASSERT_FALSE(shared_rejected.ok());
  EXPECT_EQ(shared_rejected.error().code(), ErrorCode::DepthExceed);

  MonoUniqueHolder unique;
  unique.ptr = std::make_unique<MonoUniqueNode>();
  unique.ptr->nested = std::make_unique<MonoUniqueNode>();
  auto unique_bytes = writer.serialize(unique);
  ASSERT_TRUE(unique_bytes.ok()) << unique_bytes.error().to_string();
  auto unique_rejected = reader.deserialize<MonoUniqueHolder>(
      unique_bytes->data(), unique_bytes->size());
  ASSERT_FALSE(unique_rejected.ok());
  EXPECT_EQ(unique_rejected.error().code(), ErrorCode::DepthExceed);

  MonoSharedHolder shallow;
  shallow.ptr = std::make_shared<MonoSharedNode>();
  auto shallow_bytes = writer.serialize(shallow);
  ASSERT_TRUE(shallow_bytes.ok()) << shallow_bytes.error().to_string();
  auto decoded = reader.deserialize<MonoSharedHolder>(shallow_bytes->data(),
                                                      shallow_bytes->size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  ASSERT_NE(decoded->ptr, nullptr);
}

TEST(SmartPtrSerializerTest, NonDynamicFieldConfig) {
  NonDynamicFieldHolder original;
  original.ptr = std::make_shared<PolymorphicBaseForMono>();
  original.ptr->value = 42;
  original.ptr->data = "test data";

  auto fory =
      Fory::builder().xlang(true).track_ref(false).compatible(true).build();
  fory.register_struct<NonDynamicFieldHolder>(400);
  fory.register_struct<PolymorphicBaseForMono>(401);

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<NonDynamicFieldHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  ASSERT_TRUE(deserialized.ptr);
  EXPECT_EQ(deserialized.ptr->value, 42);
  EXPECT_EQ(deserialized.ptr->data, "test data");
  EXPECT_EQ(deserialized.ptr->name(), "PolymorphicBaseForMono");
}

TEST(SmartPtrSerializerTest, NonDynamicFieldNullValue) {
  NonDynamicFieldHolder original;
  original.ptr = nullptr;

  auto fory =
      Fory::builder().xlang(true).track_ref(false).compatible(true).build();
  fory.register_struct<NonDynamicFieldHolder>(404);
  fory.register_struct<PolymorphicBaseForMono>(405);

  auto bytes_result = fory.serialize(original);
  ASSERT_TRUE(bytes_result.ok()) << bytes_result.error().to_string();

  auto deserialize_result = fory.deserialize<NonDynamicFieldHolder>(
      bytes_result->data(), bytes_result->size());
  ASSERT_TRUE(deserialize_result.ok())
      << deserialize_result.error().to_string();

  auto deserialized = std::move(deserialize_result).value();
  EXPECT_FALSE(deserialized.ptr);
}

} // namespace

} // namespace serialization
} // namespace fory
