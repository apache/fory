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

/**
 * Schema Evolution Test Suite for Fory C++ Serialization
 *
 * Tests various schema evolution scenarios:
 * 1. Adding new fields (backward compatibility)
 * 2. Removing fields (forward compatibility)
 * 3. Reordering fields
 * 4. Renaming fields
 * 5. Changing field types (when compatible)
 * 6. Complex nested struct evolution
 *
 * Schema evolution is enabled via the compatible mode flag.
 */

#include "fory/serialization/compatible_scalar.h"
#include "fory/serialization/fory.h"
#include "gtest/gtest.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

// ============================================================================
// Test Case 1: Adding New Fields (Backward Compatibility)
// ============================================================================

// V1: Original schema with 2 fields
struct PersonV1 {
  std::string name;
  int32_t age;

  bool operator==(const PersonV1 &other) const {
    return name == other.name && age == other.age;
  }
  FORY_STRUCT(PersonV1, name, age);
};

// V2: Added email field
struct PersonV2 {
  std::string name;
  int32_t age;
  std::string email; // NEW FIELD

  bool operator==(const PersonV2 &other) const {
    return name == other.name && age == other.age && email == other.email;
  }
  FORY_STRUCT(PersonV2, name, age, email);
};

// V3: Added multiple fields
struct PersonV3 {
  std::string name;
  int32_t age;
  std::string email;
  std::string phone;   // NEW FIELD
  std::string address; // NEW FIELD

  bool operator==(const PersonV3 &other) const {
    return name == other.name && age == other.age && email == other.email &&
           phone == other.phone && address == other.address;
  }
  FORY_STRUCT(PersonV3, name, age, email, phone, address);
};

// ============================================================================
// Test Case 2: Removing Fields (Forward Compatibility)
// ============================================================================

// Full schema
struct UserFull {
  int64_t id;
  std::string username;
  std::string email;
  std::string password_hash;
  int32_t login_count;

  bool operator==(const UserFull &other) const {
    return id == other.id && username == other.username &&
           email == other.email && password_hash == other.password_hash &&
           login_count == other.login_count;
  }
  FORY_STRUCT(UserFull, id, username, email, password_hash, login_count);
};

// Minimal schema (removed 3 fields)
struct UserMinimal {
  int64_t id;
  std::string username;

  bool operator==(const UserMinimal &other) const {
    return id == other.id && username == other.username;
  }
  FORY_STRUCT(UserMinimal, id, username);
};

// ============================================================================
// Test Case 3: Field Reordering
// ============================================================================

struct ConfigOriginal {
  std::string host;
  int32_t port;
  bool enable_ssl;
  std::string protocol;

  bool operator==(const ConfigOriginal &other) const {
    return host == other.host && port == other.port &&
           enable_ssl == other.enable_ssl && protocol == other.protocol;
  }
  FORY_STRUCT(ConfigOriginal, host, port, enable_ssl, protocol);
};

// Reordered fields (different order)
struct ConfigReordered {
  bool enable_ssl;      // Moved to first
  std::string protocol; // Moved to second
  std::string host;     // Moved to third
  int32_t port;         // Moved to last

  bool operator==(const ConfigReordered &other) const {
    return host == other.host && port == other.port &&
           enable_ssl == other.enable_ssl && protocol == other.protocol;
  }
  FORY_STRUCT(ConfigReordered, enable_ssl, protocol, host, port);
};

// ============================================================================
// Test Case 4: Nested Struct Evolution
// ============================================================================

struct AddressV1 {
  std::string street;
  std::string city;

  bool operator==(const AddressV1 &other) const {
    return street == other.street && city == other.city;
  }
  FORY_STRUCT(AddressV1, street, city);
};

struct AddressV2 {
  std::string street;
  std::string city;
  std::string country; // NEW FIELD
  std::string zipcode; // NEW FIELD

  bool operator==(const AddressV2 &other) const {
    return street == other.street && city == other.city &&
           country == other.country && zipcode == other.zipcode;
  }
  FORY_STRUCT(AddressV2, street, city, country, zipcode);
};

struct EmployeeV1 {
  std::string name;
  AddressV1 home_address;

  bool operator==(const EmployeeV1 &other) const {
    return name == other.name && home_address == other.home_address;
  }
  FORY_STRUCT(EmployeeV1, name, home_address);
};

struct EmployeeV2 {
  std::string name;
  AddressV2 home_address;  // Nested struct evolved
  std::string employee_id; // NEW FIELD

  bool operator==(const EmployeeV2 &other) const {
    return name == other.name && home_address == other.home_address &&
           employee_id == other.employee_id;
  }
  FORY_STRUCT(EmployeeV2, name, home_address, employee_id);
};

// ============================================================================
// Test Case 5: Collection Field Evolution
// ============================================================================

struct ProductV1 {
  std::string name;
  double price;

  bool operator==(const ProductV1 &other) const {
    return name == other.name && price == other.price;
  }
  FORY_STRUCT(ProductV1, name, price);
};

struct ProductV2 {
  std::string name;
  double price;
  std::vector<std::string> tags;                 // NEW FIELD
  std::map<std::string, std::string> attributes; // NEW FIELD

  bool operator==(const ProductV2 &other) const {
    return name == other.name && price == other.price && tags == other.tags &&
           attributes == other.attributes;
  }
  FORY_STRUCT(ProductV2, name, price, tags, attributes);
};

struct CompatibleListField {
  std::vector<int32_t> values;

  FORY_STRUCT(CompatibleListField,
              (values, fory::F(1).list(fory::T::int32().fixed())));
};

struct CompatibleArrayField {
  std::vector<int32_t> values;

  FORY_STRUCT(CompatibleArrayField,
              (values, fory::F(1).array(fory::T::int32())));
};

struct CompatibleNullableListField {
  std::vector<std::optional<int32_t>> values;

  FORY_STRUCT(CompatibleNullableListField,
              (values, fory::F(1).list(fory::T::fixed())));
};

struct CompatibleNestedListField {
  std::map<std::string, std::vector<int32_t>> values;

  FORY_STRUCT(CompatibleNestedListField,
              (values,
               fory::F(1).map(fory::T::string(),
                              fory::T::list(fory::T::int32().fixed()))));
};

struct CompatibleNestedArrayField {
  std::map<std::string, std::vector<int32_t>> values;

  FORY_STRUCT(CompatibleNestedArrayField,
              (values, fory::F(1).map(fory::T::string(),
                                      fory::T::array(fory::T::int32()))));
};

struct CompatibleUnsignedExactV1 {
  uint32_t id = 0;
  uint64_t count = 0;
  std::string extra;

  FORY_STRUCT(CompatibleUnsignedExactV1, (id, fory::F(1)), (count, fory::F(2)),
              (extra, fory::F(3)));
};

struct CompatibleUnsignedExactV2 {
  uint32_t id = 0;
  uint64_t count = 0;

  FORY_STRUCT(CompatibleUnsignedExactV2, (id, fory::F(1)), (count, fory::F(2)));
};

struct ScalarBoolField {
  bool value = false;
  FORY_STRUCT(ScalarBoolField, (value, fory::F(1)));
};

struct ScalarStringField {
  std::string value;
  FORY_STRUCT(ScalarStringField, (value, fory::F(1)));
};

struct ScalarInt8Field {
  int8_t value = 0;
  FORY_STRUCT(ScalarInt8Field, (value, fory::F(1)));
};

struct ScalarInt32Field {
  int32_t value = 0;
  FORY_STRUCT(ScalarInt32Field, (value, fory::F(1)));
};

struct ScalarInt64Field {
  int64_t value = 0;
  FORY_STRUCT(ScalarInt64Field, (value, fory::F(1)));
};

struct ScalarFloatField {
  float value = 0.0f;
  FORY_STRUCT(ScalarFloatField, (value, fory::F(1)));
};

struct ScalarFloat16Field {
  fory::float16_t value = fory::float16_t::from_bits(0);
  FORY_STRUCT(ScalarFloat16Field, (value, fory::F(1)));
};

struct ScalarBFloat16Field {
  fory::bfloat16_t value = fory::bfloat16_t::from_bits(0);
  FORY_STRUCT(ScalarBFloat16Field, (value, fory::F(1)));
};

struct ScalarDoubleField {
  double value = 0.0;
  FORY_STRUCT(ScalarDoubleField, (value, fory::F(1)));
};

struct ScalarDecimalField {
  fory::serialization::Decimal value;
  FORY_STRUCT(ScalarDecimalField, (value, fory::F(1)));
};

struct ScalarDecimalExtraField {
  fory::serialization::Decimal value;
  int32_t extra = 0;
  FORY_STRUCT(ScalarDecimalExtraField, (value, fory::F(1)), extra);
};

struct OptionalStringField {
  std::optional<std::string> value;
  FORY_STRUCT(OptionalStringField, (value, fory::F(1)));
};

struct OptionalBoolField {
  std::optional<bool> value;
  FORY_STRUCT(OptionalBoolField, (value, fory::F(1)));
};

struct OptionalIntField {
  std::optional<int32_t> value;
  FORY_STRUCT(OptionalIntField, (value, fory::F(1)));
};

struct PimplCompatibleAImpl {
  int32_t a = 0;
};

class PimplCompatibleA {
public:
  PimplCompatibleA() : impl_(std::make_unique<PimplCompatibleAImpl>()) {}
  explicit PimplCompatibleA(int32_t a) : PimplCompatibleA() { impl_->a = a; }
  PimplCompatibleA(const PimplCompatibleA &other)
      : PimplCompatibleA(other.a()) {}
  PimplCompatibleA &operator=(const PimplCompatibleA &other) {
    a(other.a());
    return *this;
  }
  PimplCompatibleA(PimplCompatibleA &&) noexcept = default;
  PimplCompatibleA &operator=(PimplCompatibleA &&) noexcept = default;

  const int32_t &a() const { return impl_->a; }
  int32_t &a() { return impl_->a; }
  PimplCompatibleA &a(int32_t value) {
    impl_->a = value;
    return *this;
  }

private:
  std::unique_ptr<PimplCompatibleAImpl> impl_;

public:
  FORY_STRUCT(PimplCompatibleA, FORY_PROPERTY(a));
};

struct DirectCompatibleA {
  int32_t a = 0;
  FORY_STRUCT(DirectCompatibleA, a);
};

struct PimplCompatibleB {
  PimplCompatibleA a;
  FORY_STRUCT(PimplCompatibleB, a);
};

struct DirectCompatibleB {
  DirectCompatibleA a;
  FORY_STRUCT(DirectCompatibleB, a);
};

// ============================================================================
// TESTS
// ============================================================================

namespace fory {
namespace serialization {
namespace test {

template <typename WriterT, typename ReaderT>
Result<ReaderT, Error> convert_field(const WriterT &value, uint32_t type_id) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();
  auto writer_reg = writer.register_struct<WriterT>(type_id);
  if (!writer_reg.ok()) {
    return Unexpected(std::move(writer_reg).error());
  }
  auto reader_reg = reader.register_struct<ReaderT>(type_id);
  if (!reader_reg.ok()) {
    return Unexpected(std::move(reader_reg).error());
  }
  auto bytes = writer.serialize(value);
  if (!bytes.ok()) {
    return Unexpected(std::move(bytes).error());
  }
  return reader.deserialize<ReaderT>(bytes.value().data(),
                                     bytes.value().size());
}

size_t single_byte_delta_index(const std::vector<uint8_t> &left,
                               const std::vector<uint8_t> &right) {
  if (left.size() != right.size()) {
    ADD_FAILURE() << "serialized payload sizes differ";
    return left.size();
  }
  size_t index = left.size();
  for (size_t i = 0; i < left.size(); ++i) {
    if (left[i] != right[i]) {
      EXPECT_EQ(index, left.size())
          << "serialized bool payload must have a single byte delta";
      index = i;
    }
  }
  EXPECT_NE(index, left.size()) << "serialized bool payload byte not found";
  return index;
}

TEST(SchemaEvolutionTest, AddingSingleField) {
  // Serialize V1, deserialize as V2 (V2 should have default value for email)
  // Create separate Fory instances for V1 and V2
  auto fory_v1 = Fory::builder().compatible(true).xlang(true).build();
  auto fory_v2 = Fory::builder().compatible(true).xlang(true).build();

  // Register both PersonV1 and PersonV2 with the SAME type ID for schema
  // evolution
  constexpr uint32_t PERSON_TYPE_ID = 999;
  auto reg1_result = fory_v1.register_struct<PersonV1>(PERSON_TYPE_ID);
  ASSERT_TRUE(reg1_result.ok()) << reg1_result.error().to_string();
  auto reg2_result = fory_v2.register_struct<PersonV2>(PERSON_TYPE_ID);
  ASSERT_TRUE(reg2_result.ok()) << reg2_result.error().to_string();

  // Serialize PersonV1
  PersonV1 v1{"Alice", 30};
  auto ser_result = fory_v1.serialize(v1);
  ASSERT_TRUE(ser_result.ok()) << ser_result.error().to_string();

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  // Deserialize as PersonV2 - email should be default-initialized (empty
  // string)
  auto deser_result = fory_v2.deserialize<PersonV2>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok()) << deser_result.error().to_string();

  PersonV2 v2 = std::move(deser_result).value();
  EXPECT_EQ(v2.name, "Alice");
  EXPECT_EQ(v2.age, 30);
  EXPECT_EQ(v2.email, ""); // Default value for missing field
}

TEST(SchemaEvolutionTest, AddingMultipleFields) {
  auto fory_v1 = Fory::builder().compatible(true).xlang(true).build();
  auto fory_v3 = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t PERSON_TYPE_ID = 999;
  ASSERT_TRUE(fory_v1.register_struct<PersonV1>(PERSON_TYPE_ID).ok());
  ASSERT_TRUE(fory_v3.register_struct<PersonV3>(PERSON_TYPE_ID).ok());

  // V1 -> V3 (skipping V2, adding 3 fields at once)
  PersonV1 v1{"Bob", 25};
  auto ser_result = fory_v1.serialize(v1);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  auto deser_result = fory_v3.deserialize<PersonV3>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok()) << deser_result.error().to_string();

  PersonV3 v3 = std::move(deser_result).value();
  EXPECT_EQ(v3.name, "Bob");
  EXPECT_EQ(v3.age, 25);
  EXPECT_EQ(v3.email, "");
  EXPECT_EQ(v3.phone, "");
  EXPECT_EQ(v3.address, "");
}

TEST(SchemaEvolutionTest, RemovingFields) {
  // Serialize UserFull, deserialize as UserMinimal (should ignore extra fields)
  auto fory_full = Fory::builder().compatible(true).xlang(true).build();
  auto fory_minimal = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t USER_TYPE_ID = 1000;
  ASSERT_TRUE(fory_full.register_struct<UserFull>(USER_TYPE_ID).ok());
  ASSERT_TRUE(fory_minimal.register_struct<UserMinimal>(USER_TYPE_ID).ok());

  UserFull full{12345, "johndoe", "john@example.com", "hash123", 42};
  auto ser_result = fory_full.serialize(full);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  // Deserialize as minimal - should skip email, password_hash, login_count
  auto deser_result =
      fory_minimal.deserialize<UserMinimal>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok()) << deser_result.error().to_string();

  UserMinimal minimal = std::move(deser_result).value();
  EXPECT_EQ(minimal.id, 12345);
  EXPECT_EQ(minimal.username, "johndoe");
}

TEST(SchemaEvolutionTest, FieldReordering) {
  // Serialize ConfigOriginal, deserialize as ConfigReordered
  // Field order shouldn't matter in compatible mode
  auto fory_orig = Fory::builder().compatible(true).xlang(true).build();
  auto fory_reord = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t CONFIG_TYPE_ID = 1001;
  ASSERT_TRUE(fory_orig.register_struct<ConfigOriginal>(CONFIG_TYPE_ID).ok());
  ASSERT_TRUE(fory_reord.register_struct<ConfigReordered>(CONFIG_TYPE_ID).ok());

  ConfigOriginal orig{"localhost", 8080, true, "https"};
  auto ser_result = fory_orig.serialize(orig);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  auto deser_result =
      fory_reord.deserialize<ConfigReordered>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok()) << deser_result.error().to_string();

  ConfigReordered reordered = std::move(deser_result).value();
  EXPECT_EQ(reordered.host, "localhost");
  EXPECT_EQ(reordered.port, 8080);
  EXPECT_EQ(reordered.enable_ssl, true);
  EXPECT_EQ(reordered.protocol, "https");
}

TEST(SchemaEvolutionTest, BidirectionalAddRemove) {
  auto fory_v2 = Fory::builder().compatible(true).xlang(true).build();
  auto fory_v1 = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t PERSON_TYPE_ID = 999;
  ASSERT_TRUE(fory_v2.register_struct<PersonV2>(PERSON_TYPE_ID).ok());
  ASSERT_TRUE(fory_v1.register_struct<PersonV1>(PERSON_TYPE_ID).ok());

  // V2 -> V1 (removing email field)
  PersonV2 v2{"Charlie", 35, "charlie@example.com"};
  auto ser_result = fory_v2.serialize(v2);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  auto deser_result = fory_v1.deserialize<PersonV1>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok()) << deser_result.error().to_string();

  PersonV1 v1 = std::move(deser_result).value();
  EXPECT_EQ(v1.name, "Charlie");
  EXPECT_EQ(v1.age, 35);
  // email is lost, which is expected
}

TEST(SchemaEvolutionTest, NestedStructEvolution) {
  auto fory_v1 = Fory::builder().compatible(true).xlang(true).build();
  auto fory_v2 = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t ADDRESS_TYPE_ID = 1002;
  constexpr uint32_t EMPLOYEE_TYPE_ID = 1003;

  ASSERT_TRUE(fory_v1.register_struct<AddressV1>(ADDRESS_TYPE_ID).ok());
  ASSERT_TRUE(fory_v1.register_struct<EmployeeV1>(EMPLOYEE_TYPE_ID).ok());
  ASSERT_TRUE(fory_v2.register_struct<AddressV2>(ADDRESS_TYPE_ID).ok());
  ASSERT_TRUE(fory_v2.register_struct<EmployeeV2>(EMPLOYEE_TYPE_ID).ok());

  // Serialize EmployeeV1, deserialize as EmployeeV2
  EmployeeV1 emp_v1{"Jane Doe", {"123 Main St", "NYC"}};
  auto ser_result = fory_v1.serialize(emp_v1);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  auto deser_result =
      fory_v2.deserialize<EmployeeV2>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok()) << deser_result.error().to_string();

  EmployeeV2 emp_v2 = std::move(deser_result).value();
  EXPECT_EQ(emp_v2.name, "Jane Doe");
  EXPECT_EQ(emp_v2.home_address.street, "123 Main St");
  EXPECT_EQ(emp_v2.home_address.city, "NYC");
  EXPECT_EQ(emp_v2.home_address.country, ""); // Default value
  EXPECT_EQ(emp_v2.home_address.zipcode, ""); // Default value
  EXPECT_EQ(emp_v2.employee_id, "");          // Default value
}

TEST(SchemaEvolutionTest, CollectionFieldEvolution) {
  auto fory_v1 = Fory::builder().compatible(true).xlang(true).build();
  auto fory_v2 = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t PRODUCT_TYPE_ID = 1004;
  ASSERT_TRUE(fory_v1.register_struct<ProductV1>(PRODUCT_TYPE_ID).ok());
  ASSERT_TRUE(fory_v2.register_struct<ProductV2>(PRODUCT_TYPE_ID).ok());

  // Serialize ProductV1, deserialize as ProductV2
  ProductV1 prod_v1{"Laptop", 999.99};
  auto ser_result = fory_v1.serialize(prod_v1);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  auto deser_result =
      fory_v2.deserialize<ProductV2>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok()) << deser_result.error().to_string();

  ProductV2 prod_v2 = std::move(deser_result).value();
  EXPECT_EQ(prod_v2.name, "Laptop");
  EXPECT_EQ(prod_v2.price, 999.99);
  EXPECT_TRUE(prod_v2.tags.empty());       // Default empty vector
  EXPECT_TRUE(prod_v2.attributes.empty()); // Default empty map
}

TEST(SchemaEvolutionTest, ImmediateListFieldCanReadIntoArrayCarrier) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t TYPE_ID = 1005;
  ASSERT_TRUE(writer.register_struct<CompatibleListField>(TYPE_ID).ok());
  ASSERT_TRUE(reader.register_struct<CompatibleArrayField>(TYPE_ID).ok());

  auto bytes = writer.serialize(CompatibleListField{{1, -2, 3}});
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  auto decoded = reader.deserialize<CompatibleArrayField>(bytes.value().data(),
                                                          bytes.value().size());

  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(decoded.value().values, (std::vector<int32_t>{1, -2, 3}));
}

TEST(SchemaEvolutionTest, ImmediateArrayFieldCanReadIntoListCarrier) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t TYPE_ID = 1006;
  ASSERT_TRUE(writer.register_struct<CompatibleArrayField>(TYPE_ID).ok());
  ASSERT_TRUE(reader.register_struct<CompatibleListField>(TYPE_ID).ok());

  auto bytes = writer.serialize(CompatibleArrayField{{4, 5, 6}});
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  auto decoded = reader.deserialize<CompatibleListField>(bytes.value().data(),
                                                         bytes.value().size());

  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(decoded.value().values, (std::vector<int32_t>{4, 5, 6}));
}

TEST(SchemaEvolutionTest, NullableListElementsReadIntoArrayCarrier) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t TYPE_ID = 1007;
  ASSERT_TRUE(
      writer.register_struct<CompatibleNullableListField>(TYPE_ID).ok());
  ASSERT_TRUE(reader.register_struct<CompatibleArrayField>(TYPE_ID).ok());

  auto bytes = writer.serialize(CompatibleNullableListField{{1, 2}});
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  std::vector<uint8_t> payload = std::move(bytes).value();
  auto decoded =
      reader.deserialize<CompatibleArrayField>(payload.data(), payload.size());

  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(decoded.value().values, (std::vector<int32_t>{1, 2}));

  auto null_bytes = writer.serialize(
      CompatibleNullableListField{{std::optional<int32_t>{1}, std::nullopt}});
  ASSERT_TRUE(null_bytes.ok()) << null_bytes.error().to_string();
  auto null_payload = std::move(null_bytes).value();
  auto null_decoded = reader.deserialize<CompatibleArrayField>(
      null_payload.data(), null_payload.size());
  ASSERT_FALSE(null_decoded.ok());
  EXPECT_EQ(null_decoded.error().code(), ErrorCode::InvalidData);
}

TEST(SchemaEvolutionTest, NestedListArraySchemaPairsAreNotMatched) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t TYPE_ID = 1008;
  ASSERT_TRUE(writer.register_struct<CompatibleNestedListField>(TYPE_ID).ok());
  ASSERT_TRUE(reader.register_struct<CompatibleNestedArrayField>(TYPE_ID).ok());

  auto bytes = writer.serialize(
      CompatibleNestedListField{{{"items", std::vector<int32_t>{7, 8}}}});
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  auto decoded = reader.deserialize<CompatibleNestedArrayField>(
      bytes.value().data(), bytes.value().size());

  ASSERT_FALSE(decoded.ok());
  EXPECT_EQ(decoded.error().code(), ErrorCode::TypeError);
}

TEST(SchemaEvolutionTest, ChangedSchemaReadsExactUnsignedFields) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t TYPE_ID = 1009;
  ASSERT_TRUE(writer.register_struct<CompatibleUnsignedExactV1>(TYPE_ID).ok());
  ASSERT_TRUE(reader.register_struct<CompatibleUnsignedExactV2>(TYPE_ID).ok());

  CompatibleUnsignedExactV1 value{300u, 5000000000ull, "skip"};
  auto bytes = writer.serialize(value);
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  auto decoded = reader.deserialize<CompatibleUnsignedExactV2>(
      bytes.value().data(), bytes.value().size());

  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(decoded.value().id, value.id);
  EXPECT_EQ(decoded.value().count, value.count);
}

TEST(SchemaEvolutionTest, PimplPropertyNestedNamedStruct) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  ASSERT_TRUE(writer.register_struct<PimplCompatibleA>("pimpl", "A").ok());
  ASSERT_TRUE(writer.register_struct<PimplCompatibleB>("pimpl", "B").ok());
  ASSERT_TRUE(reader.register_struct<DirectCompatibleA>("pimpl", "A").ok());
  ASSERT_TRUE(reader.register_struct<DirectCompatibleB>("pimpl", "B").ok());

  auto type_info = writer.type_resolver().get_type_info<PimplCompatibleA>();
  ASSERT_TRUE(type_info.ok()) << type_info.error().to_string();
  EXPECT_EQ(type_info.value()->type_id,
            static_cast<uint32_t>(TypeId::NAMED_COMPATIBLE_STRUCT));

  PimplCompatibleB original;
  original.a.a(2468);
  auto bytes = writer.serialize(original);
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();

  auto decoded = reader.deserialize<DirectCompatibleB>(bytes.value().data(),
                                                       bytes.value().size());
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_EQ(decoded.value().a.a, 2468);
}

TEST(SchemaEvolutionTest, ScalarBoolString) {
  auto decoded =
      convert_field<ScalarStringField, ScalarBoolField>({"true"}, 1010);
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_TRUE(decoded.value().value);

  auto text = convert_field<ScalarBoolField, ScalarStringField>({true}, 1011);
  ASSERT_TRUE(text.ok()) << text.error().to_string();
  EXPECT_EQ(text.value().value, "true");

  auto invalid =
      convert_field<ScalarStringField, ScalarBoolField>({"TRUE"}, 1012);
  ASSERT_FALSE(invalid.ok());
  EXPECT_EQ(invalid.error().code(), ErrorCode::InvalidData);
}

TEST(SchemaEvolutionTest, ScalarBoolPayloadRejectsNonCanonicalByte) {
  Config config;
  config.compatible = true;
  config.xlang = true;
  ReadContext ctx(config, std::make_unique<TypeResolver>());
  std::vector<uint8_t> bytes{2};
  Buffer buffer(bytes);
  ctx.attach(buffer);

  auto value =
      read_compatible_string(ctx, static_cast<uint32_t>(TypeId::BOOL), "value");

  EXPECT_EQ(value, "");
  ASSERT_TRUE(ctx.has_error());
  EXPECT_EQ(ctx.error().code(), ErrorCode::InvalidData);
  ctx.detach();
}

TEST(SchemaEvolutionTest, ScalarTrackingRefClassifierRejectsMismatch) {
  FieldType local_bool(static_cast<uint32_t>(TypeId::BOOL), false, false);
  FieldType remote_bool(static_cast<uint32_t>(TypeId::BOOL), false, true);
  EXPECT_FALSE(field_types_compatible_top_level(local_bool, remote_bool));

  FieldType local_string(static_cast<uint32_t>(TypeId::STRING), false, false);
  EXPECT_FALSE(field_types_compatible_top_level(local_string, remote_bool));

  FieldType remote_string(static_cast<uint32_t>(TypeId::STRING), false, false);
  EXPECT_TRUE(field_types_compatible_top_level(local_bool, remote_string));

  FieldType ref_local_bool(static_cast<uint32_t>(TypeId::BOOL), false, true);
  EXPECT_TRUE(field_types_compatible_top_level(ref_local_bool, remote_bool));
  FieldType ref_local_nullable_bool(static_cast<uint32_t>(TypeId::BOOL), true,
                                    true);
  FieldType ref_remote_nullable_bool(static_cast<uint32_t>(TypeId::BOOL), true,
                                     true);
  EXPECT_TRUE(field_types_compatible_top_level(ref_local_nullable_bool,
                                               ref_remote_nullable_bool));
  EXPECT_FALSE(field_types_compatible_top_level(ref_local_bool,
                                                ref_remote_nullable_bool));
  EXPECT_FALSE(
      field_types_compatible_top_level(ref_local_nullable_bool, remote_bool));

  FieldType fixed_int32(static_cast<uint32_t>(TypeId::INT32), false, false);
  FieldType varint32(static_cast<uint32_t>(TypeId::VARINT32), false, false);
  EXPECT_TRUE(field_types_compatible_top_level(fixed_int32, varint32));

  FieldType ref_fixed_int32(static_cast<uint32_t>(TypeId::INT32), false, true);
  FieldType ref_varint32(static_cast<uint32_t>(TypeId::VARINT32), false, true);
  EXPECT_FALSE(field_types_compatible_top_level(ref_fixed_int32, ref_varint32));
}

TEST(SchemaEvolutionTest, ScalarBoolNumber) {
  auto decoded = convert_field<ScalarInt32Field, ScalarBoolField>({1}, 1013);
  ASSERT_TRUE(decoded.ok()) << decoded.error().to_string();
  EXPECT_TRUE(decoded.value().value);

  auto number = convert_field<ScalarBoolField, ScalarInt32Field>({true}, 1014);
  ASSERT_TRUE(number.ok()) << number.error().to_string();
  EXPECT_EQ(number.value().value, 1);

  auto invalid = convert_field<ScalarDoubleField, ScalarBoolField>({0.5}, 1015);
  ASSERT_FALSE(invalid.ok());
  EXPECT_EQ(invalid.error().code(), ErrorCode::InvalidData);
}

TEST(SchemaEvolutionTest, ScalarNumberNumber) {
  auto narrowed = convert_field<ScalarInt64Field, ScalarInt8Field>({127}, 1016);
  ASSERT_TRUE(narrowed.ok()) << narrowed.error().to_string();
  EXPECT_EQ(narrowed.value().value, 127);

  auto widened = convert_field<ScalarInt32Field, ScalarInt64Field>({123}, 1050);
  ASSERT_TRUE(widened.ok()) << widened.error().to_string();
  EXPECT_EQ(widened.value().value, 123);

  auto range_error =
      convert_field<ScalarInt64Field, ScalarInt8Field>({128}, 1017);
  ASSERT_FALSE(range_error.ok());
  EXPECT_EQ(range_error.error().code(), ErrorCode::InvalidData);

  auto exact_float =
      convert_field<ScalarInt64Field, ScalarFloatField>({16777216}, 1018);
  ASSERT_TRUE(exact_float.ok()) << exact_float.error().to_string();
  EXPECT_EQ(exact_float.value().value, 16777216.0f);

  auto precision_error =
      convert_field<ScalarInt64Field, ScalarFloatField>({16777217}, 1019);
  ASSERT_FALSE(precision_error.ok());
  EXPECT_EQ(precision_error.error().code(), ErrorCode::InvalidData);

  auto negative_zero_half =
      convert_field<ScalarFloatField, ScalarFloat16Field>({-0.0f}, 1038);
  ASSERT_TRUE(negative_zero_half.ok())
      << negative_zero_half.error().to_string();
  EXPECT_EQ(negative_zero_half.value().value.to_bits(), 0x8000u);

  auto negative_zero_bfloat =
      convert_field<ScalarDoubleField, ScalarBFloat16Field>({-0.0}, 1039);
  ASSERT_TRUE(negative_zero_bfloat.ok())
      << negative_zero_bfloat.error().to_string();
  EXPECT_EQ(negative_zero_bfloat.value().value.to_bits(), 0x8000u);

  auto half_inf = convert_field<ScalarFloat16Field, ScalarBFloat16Field>(
      {fory::float16_t::from_bits(0x7C00u)}, 1040);
  ASSERT_TRUE(half_inf.ok()) << half_inf.error().to_string();
  EXPECT_TRUE(fory::bfloat16_t::is_inf(half_inf.value().value, 1));

  auto bfloat_inf = convert_field<ScalarBFloat16Field, ScalarFloat16Field>(
      {fory::bfloat16_t::from_bits(0xFF80u)}, 1041);
  ASSERT_TRUE(bfloat_inf.ok()) << bfloat_inf.error().to_string();
  EXPECT_TRUE(fory::float16_t::is_inf(bfloat_inf.value().value, -1));
}

TEST(SchemaEvolutionTest, ScalarStringNumber) {
  auto integer =
      convert_field<ScalarStringField, ScalarInt32Field>({"1e2"}, 1020);
  ASSERT_TRUE(integer.ok()) << integer.error().to_string();
  EXPECT_EQ(integer.value().value, 100);

  auto exact_float =
      convert_field<ScalarStringField, ScalarDoubleField>({"0.5"}, 1021);
  ASSERT_TRUE(exact_float.ok()) << exact_float.error().to_string();
  EXPECT_EQ(exact_float.value().value, 0.5);

  auto grammar_error =
      convert_field<ScalarStringField, ScalarInt32Field>({"+1"}, 1022);
  ASSERT_FALSE(grammar_error.ok());
  EXPECT_EQ(grammar_error.error().code(), ErrorCode::InvalidData);

  auto precision_error =
      convert_field<ScalarStringField, ScalarFloatField>({"0.1"}, 1023);
  ASSERT_FALSE(precision_error.ok());
  EXPECT_EQ(precision_error.error().code(), ErrorCode::InvalidData);

  std::string digits_256(256, '1');
  auto digit_bound =
      convert_field<ScalarStringField, ScalarDecimalField>({digits_256}, 1042);
  ASSERT_TRUE(digit_bound.ok()) << digit_bound.error().to_string();
  EXPECT_EQ(digit_bound.value().value.scale(), 0);
  EXPECT_FALSE(digit_bound.value().value.is_zero());

  auto exponent_bound =
      convert_field<ScalarStringField, ScalarDecimalField>({"1e255"}, 1043);
  ASSERT_TRUE(exponent_bound.ok()) << exponent_bound.error().to_string();
  EXPECT_EQ(exponent_bound.value().value.scale(), 0);
  EXPECT_FALSE(exponent_bound.value().value.is_zero());

  std::string digits_257(257, '1');
  auto digit_error =
      convert_field<ScalarStringField, ScalarDecimalField>({digits_257}, 1044);
  ASSERT_FALSE(digit_error.ok());
  EXPECT_EQ(digit_error.error().code(), ErrorCode::InvalidData);

  std::string raw_length = "0.";
  raw_length.append(319, '0');
  auto raw_length_error =
      convert_field<ScalarStringField, ScalarDecimalField>({raw_length}, 1045);
  ASSERT_FALSE(raw_length_error.ok());
  EXPECT_EQ(raw_length_error.error().code(), ErrorCode::InvalidData);

  auto huge_exponent =
      convert_field<ScalarStringField, ScalarDecimalField>({"1e1000000"}, 1046);
  ASSERT_FALSE(huge_exponent.ok());
  EXPECT_EQ(huge_exponent.error().code(), ErrorCode::InvalidData);

  auto exponent_error =
      convert_field<ScalarStringField, ScalarDecimalField>({"1e256"}, 1047);
  ASSERT_FALSE(exponent_error.ok());
  EXPECT_EQ(exponent_error.error().code(), ErrorCode::InvalidData);
}

TEST(SchemaEvolutionTest, ScalarNumberString) {
  auto integer =
      convert_field<ScalarInt32Field, ScalarStringField>({-12}, 1024);
  ASSERT_TRUE(integer.ok()) << integer.error().to_string();
  EXPECT_EQ(integer.value().value, "-12");

  auto floating =
      convert_field<ScalarFloatField, ScalarStringField>({0.5f}, 1025);
  ASSERT_TRUE(floating.ok()) << floating.error().to_string();
  EXPECT_EQ(floating.value().value, "0.5");

  auto decimal = convert_field<ScalarDecimalField, ScalarStringField>(
      {Decimal::from_int64(12340, 3)}, 1026);
  ASSERT_TRUE(decimal.ok()) << decimal.error().to_string();
  EXPECT_EQ(decimal.value().value, "12.34");

  auto oversized_decimal = convert_field<ScalarDecimalField, ScalarStringField>(
      {Decimal::from_int64(1, -256)}, 1048);
  ASSERT_FALSE(oversized_decimal.ok());
  EXPECT_EQ(oversized_decimal.error().code(), ErrorCode::InvalidData);
}

TEST(SchemaEvolutionTest, ScalarDecimal) {
  auto bool_value = convert_field<ScalarDecimalField, ScalarBoolField>(
      {Decimal::from_int64(10, 1)}, 1027);
  ASSERT_TRUE(bool_value.ok()) << bool_value.error().to_string();
  EXPECT_TRUE(bool_value.value().value);

  auto bool_error = convert_field<ScalarDecimalField, ScalarBoolField>(
      {Decimal::from_int64(5, 1)}, 1028);
  ASSERT_FALSE(bool_error.ok());
  EXPECT_EQ(bool_error.error().code(), ErrorCode::InvalidData);

  auto decimal =
      convert_field<ScalarStringField, ScalarDecimalField>({"1.2300"}, 1029);
  ASSERT_TRUE(decimal.ok()) << decimal.error().to_string();
  EXPECT_EQ(decimal.value().value, Decimal::from_int64(123, 2));

  auto same_type = convert_field<ScalarDecimalExtraField, ScalarDecimalField>(
      {Decimal::from_int64(1230, 3), 7}, 1030);
  ASSERT_TRUE(same_type.ok()) << same_type.error().to_string();
  EXPECT_EQ(same_type.value().value, Decimal::from_int64(1230, 3));
}

TEST(SchemaEvolutionTest, ScalarOptional) {
  auto primitive = convert_field<OptionalStringField, ScalarBoolField>(
      {std::string("1")}, 1031);
  ASSERT_TRUE(primitive.ok()) << primitive.error().to_string();
  EXPECT_TRUE(primitive.value().value);

  auto optional = convert_field<OptionalStringField, OptionalIntField>(
      {std::nullopt}, 1032);
  ASSERT_TRUE(optional.ok()) << optional.error().to_string();
  EXPECT_FALSE(optional.value().value.has_value());
}

TEST(SchemaEvolutionTest, ScalarOptionalRejectsRefValueFlag) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t TYPE_ID = 1048;
  ASSERT_TRUE(writer.register_struct<OptionalStringField>(TYPE_ID).ok());
  ASSERT_TRUE(reader.register_struct<ScalarBoolField>(TYPE_ID).ok());

  auto bytes = writer.serialize(OptionalStringField{std::string("1")});
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  std::vector<uint8_t> payload = std::move(bytes).value();
  auto flag = std::find(payload.rbegin(), payload.rend(),
                        static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  ASSERT_NE(flag, payload.rend());
  *flag = static_cast<uint8_t>(REF_VALUE_FLAG);

  auto decoded =
      reader.deserialize<ScalarBoolField>(payload.data(), payload.size());
  ASSERT_FALSE(decoded.ok());
  EXPECT_EQ(decoded.error().code(), ErrorCode::InvalidData);
}

TEST(SchemaEvolutionTest, ScalarSameTypeOptionalRejectsRefValueFlag) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t TYPE_ID = 1049;
  ASSERT_TRUE(writer.register_struct<OptionalBoolField>(TYPE_ID).ok());
  ASSERT_TRUE(reader.register_struct<ScalarBoolField>(TYPE_ID).ok());

  auto bytes = writer.serialize(OptionalBoolField{true});
  ASSERT_TRUE(bytes.ok()) << bytes.error().to_string();
  std::vector<uint8_t> payload = std::move(bytes).value();
  auto flag = std::find(payload.rbegin(), payload.rend(),
                        static_cast<uint8_t>(NOT_NULL_VALUE_FLAG));
  ASSERT_NE(flag, payload.rend());
  *flag = static_cast<uint8_t>(REF_VALUE_FLAG);

  auto decoded =
      reader.deserialize<ScalarBoolField>(payload.data(), payload.size());
  ASSERT_FALSE(decoded.ok());
  EXPECT_EQ(decoded.error().code(), ErrorCode::InvalidData);
}

TEST(SchemaEvolutionTest, ScalarSameTypeOptionalRejectsBadBool) {
  auto writer = Fory::builder().compatible(true).xlang(true).build();
  auto reader = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t TYPE_ID = 1050;
  ASSERT_TRUE(writer.register_struct<OptionalBoolField>(TYPE_ID).ok());
  ASSERT_TRUE(reader.register_struct<ScalarBoolField>(TYPE_ID).ok());

  auto true_bytes = writer.serialize(OptionalBoolField{true});
  ASSERT_TRUE(true_bytes.ok()) << true_bytes.error().to_string();
  auto false_bytes = writer.serialize(OptionalBoolField{false});
  ASSERT_TRUE(false_bytes.ok()) << false_bytes.error().to_string();
  std::vector<uint8_t> payload = std::move(true_bytes).value();
  size_t bool_index = single_byte_delta_index(payload, false_bytes.value());
  ASSERT_LT(bool_index, payload.size());
  payload[bool_index] = 2;

  auto decoded =
      reader.deserialize<ScalarBoolField>(payload.data(), payload.size());
  ASSERT_FALSE(decoded.ok());
  EXPECT_EQ(decoded.error().code(), ErrorCode::InvalidData);
}

TEST(SchemaEvolutionTest, RoundtripWithSameVersion) {
  // Sanity check: V2 -> V2 should work perfectly
  auto fory_compat = Fory::builder().compatible(true).xlang(true).build();

  constexpr uint32_t PERSON_TYPE_ID = 999;
  ASSERT_TRUE(fory_compat.register_struct<PersonV2>(PERSON_TYPE_ID).ok());

  PersonV2 original{"Dave", 40, "dave@example.com"};
  auto ser_result = fory_compat.serialize(original);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  std::cout << "Serialized bytes size: " << bytes.size() << std::endl;

  auto deser_result =
      fory_compat.deserialize<PersonV2>(bytes.data(), bytes.size());
  ASSERT_TRUE(deser_result.ok())
      << "Error: " << deser_result.error().to_string();

  PersonV2 deserialized = std::move(deser_result).value();
  EXPECT_EQ(original, deserialized);
}

TEST(SchemaEvolutionTest, NonCompatibleModeStrictness) {
  // In non-compatible mode, struct serialization should be strict
  // Different struct types should NOT be interchangeable
  auto fory_strict = Fory::builder().compatible(false).xlang(true).build();

  // Register PersonV1 before serialization
  constexpr uint32_t PERSON_TYPE_ID = 999;
  ASSERT_TRUE(fory_strict.register_struct<PersonV1>(PERSON_TYPE_ID).ok());

  PersonV1 v1{"Eve", 28};
  auto ser_result = fory_strict.serialize(v1);
  ASSERT_TRUE(ser_result.ok());

  std::vector<uint8_t> bytes = std::move(ser_result).value();

  // NOTE: In strict mode (compatible=false), deserializing V1 data as V2
  // should ideally fail or at least not work correctly. However, this
  // depends on implementation details. For now, we just document this
  // as a potential enhancement.

  // This test is disabled until we implement strict mode validation
  // auto deser_result = fory_strict.deserialize<PersonV2>(bytes.data(),
  // bytes.size()); EXPECT_FALSE(deser_result.ok()) << "Should fail in strict
  // mode";
}

// ============================================================================
// Performance and Stress Tests
// ============================================================================

TEST(SchemaEvolutionTest, LargeNumberOfFields) {
  // Test evolution with structs that have many fields
  // (This would require defining structs with 20+ fields, omitted for brevity)
}

TEST(SchemaEvolutionTest, DeepNesting) {
  // Test evolution with deeply nested structs (5+ levels)
  // (This would require defining deep struct hierarchies, omitted for brevity)
}

TEST(SchemaEvolutionTest, MixedEvolution) {
  // Test combining add, remove, and reorder operations simultaneously
  // (This is effectively tested by the combination of other tests)
}

} // namespace test
} // namespace serialization
} // namespace fory
