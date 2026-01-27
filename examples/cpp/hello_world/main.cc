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
 * Fory C++ Serialization Example
 *
 * This example demonstrates how to use Fory for high-performance
 * serialization and deserialization of C++ objects.
 */

#include <cstdint>
#include <iostream>
#include <map>
#include <string>
#include <vector>

#include "fory/serialization/fory.h"

// Define a simple struct with primitive fields
struct Point {
  int32_t x;
  int32_t y;

  bool operator==(const Point &other) const {
    return x == other.x && y == other.y;
  }

  FORY_STRUCT(Point, x, y);
};

// Define a more complex struct with various field types
struct Person {
  std::string name;
  int32_t age;
  std::vector<std::string> hobbies;

  bool operator==(const Person &other) const {
    return name == other.name && age == other.age && hobbies == other.hobbies;
  }

  FORY_STRUCT(Person, name, age, hobbies);
};

// Define a nested struct
struct Team {
  std::string team_name;
  std::vector<Person> members;
  Point headquarters;

  bool operator==(const Team &other) const {
    return team_name == other.team_name && members == other.members &&
           headquarters == other.headquarters;
  }

  FORY_STRUCT(Team, team_name, members, headquarters);
};

// Define an enum
enum class Status { PENDING, ACTIVE, COMPLETED };

// Helper function to print bytes
void print_bytes(const std::vector<uint8_t> &bytes) {
  std::cout << "Serialized bytes (" << bytes.size() << " bytes): ";
  for (size_t i = 0; i < std::min(bytes.size(), size_t(20)); ++i) {
    printf("%02x ", bytes[i]);
  }
  if (bytes.size() > 20) {
    std::cout << "...";
  }
  std::cout << std::endl;
}

int main() {
  std::cout << "=== Fory C++ Serialization Example ===" << std::endl
            << std::endl;

  // Create a Fory instance with xlang (cross-language) mode enabled
  auto fory = fory::serialization::Fory::builder()
                  .xlang(true)      // Enable cross-language serialization
                  .track_ref(false) // Disable reference tracking for simplicity
                  .build();

  // Register struct types (required for struct serialization)
  fory.register_struct<Point>(1);
  fory.register_struct<Person>(2);
  fory.register_struct<Team>(3);

  // ============================================================================
  // Example 1: Primitive types
  // ============================================================================
  std::cout << "--- Example 1: Primitive Types ---" << std::endl;
  {
    int32_t original = 42;
    auto bytes_result = fory.serialize(original);
    if (bytes_result.ok()) {
      auto bytes = bytes_result.value();
      print_bytes(bytes);

      auto result = fory.deserialize<int32_t>(bytes.data(), bytes.size());
      if (result.ok()) {
        std::cout << "Original: " << original
                  << ", Deserialized: " << result.value() << std::endl;
      }
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 2: String
  // ============================================================================
  std::cout << "--- Example 2: String ---" << std::endl;
  {
    std::string original = "Hello, Fory!";
    auto bytes_result = fory.serialize(original);
    if (bytes_result.ok()) {
      auto bytes = bytes_result.value();
      print_bytes(bytes);

      auto result = fory.deserialize<std::string>(bytes.data(), bytes.size());
      if (result.ok()) {
        std::cout << "Original: \"" << original << "\", Deserialized: \""
                  << result.value() << "\"" << std::endl;
      }
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 3: Vector
  // ============================================================================
  std::cout << "--- Example 3: Vector ---" << std::endl;
  {
    std::vector<int32_t> original = {1, 2, 3, 4, 5};
    auto bytes_result = fory.serialize(original);
    if (bytes_result.ok()) {
      auto bytes = bytes_result.value();
      print_bytes(bytes);

      auto result =
          fory.deserialize<std::vector<int32_t>>(bytes.data(), bytes.size());
      if (result.ok()) {
        std::cout << "Original: [";
        for (size_t i = 0; i < original.size(); ++i) {
          std::cout << original[i] << (i < original.size() - 1 ? ", " : "");
        }
        std::cout << "], Deserialized: [";
        auto &v = result.value();
        for (size_t i = 0; i < v.size(); ++i) {
          std::cout << v[i] << (i < v.size() - 1 ? ", " : "");
        }
        std::cout << "]" << std::endl;
      }
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 4: Map
  // ============================================================================
  std::cout << "--- Example 4: Map ---" << std::endl;
  {
    std::map<std::string, int32_t> original = {
        {"apple", 1}, {"banana", 2}, {"cherry", 3}};
    auto bytes_result = fory.serialize(original);
    if (bytes_result.ok()) {
      auto bytes = bytes_result.value();
      print_bytes(bytes);

      auto result = fory.deserialize<std::map<std::string, int32_t>>(
          bytes.data(), bytes.size());
      if (result.ok()) {
        std::cout << "Original: {";
        for (auto it = original.begin(); it != original.end(); ++it) {
          std::cout << "\"" << it->first << "\": " << it->second;
          if (std::next(it) != original.end())
            std::cout << ", ";
        }
        std::cout << "}" << std::endl;

        auto &m = result.value();
        std::cout << "Deserialized: {";
        for (auto it = m.begin(); it != m.end(); ++it) {
          std::cout << "\"" << it->first << "\": " << it->second;
          if (std::next(it) != m.end())
            std::cout << ", ";
        }
        std::cout << "}" << std::endl;
      }
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 5: Simple Struct
  // ============================================================================
  std::cout << "--- Example 5: Simple Struct ---" << std::endl;
  {
    Point original{10, 20};
    auto bytes_result = fory.serialize(original);
    if (bytes_result.ok()) {
      auto bytes = bytes_result.value();
      print_bytes(bytes);

      auto result = fory.deserialize<Point>(bytes.data(), bytes.size());
      if (result.ok()) {
        auto &p = result.value();
        std::cout << "Original: Point{x=" << original.x << ", y=" << original.y
                  << "}" << std::endl;
        std::cout << "Deserialized: Point{x=" << p.x << ", y=" << p.y << "}"
                  << std::endl;
        std::cout << "Equal: " << (original == p ? "true" : "false")
                  << std::endl;
      }
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 6: Complex Struct
  // ============================================================================
  std::cout << "--- Example 6: Complex Struct ---" << std::endl;
  {
    Person original{"Alice", 30, {"reading", "coding", "hiking"}};
    auto bytes_result = fory.serialize(original);
    if (bytes_result.ok()) {
      auto bytes = bytes_result.value();
      print_bytes(bytes);

      auto result = fory.deserialize<Person>(bytes.data(), bytes.size());
      if (result.ok()) {
        auto &p = result.value();
        std::cout << "Original: Person{name=\"" << original.name
                  << "\", age=" << original.age << ", hobbies=[";
        for (size_t i = 0; i < original.hobbies.size(); ++i) {
          std::cout << "\"" << original.hobbies[i] << "\""
                    << (i < original.hobbies.size() - 1 ? ", " : "");
        }
        std::cout << "]}" << std::endl;

        std::cout << "Deserialized: Person{name=\"" << p.name
                  << "\", age=" << p.age << ", hobbies=[";
        for (size_t i = 0; i < p.hobbies.size(); ++i) {
          std::cout << "\"" << p.hobbies[i] << "\""
                    << (i < p.hobbies.size() - 1 ? ", " : "");
        }
        std::cout << "]}" << std::endl;
        std::cout << "Equal: " << (original == p ? "true" : "false")
                  << std::endl;
      }
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 7: Nested Struct
  // ============================================================================
  std::cout << "--- Example 7: Nested Struct ---" << std::endl;
  {
    Team original{"Engineering",
                  {{"Bob", 25, {"gaming"}}, {"Carol", 28, {"music", "art"}}},
                  {100, 200}};
    auto bytes_result = fory.serialize(original);
    if (bytes_result.ok()) {
      auto bytes = bytes_result.value();
      print_bytes(bytes);

      auto result = fory.deserialize<Team>(bytes.data(), bytes.size());
      if (result.ok()) {
        auto &t = result.value();
        std::cout << "Original team: \"" << original.team_name << "\" with "
                  << original.members.size() << " members" << std::endl;
        std::cout << "Deserialized team: \"" << t.team_name << "\" with "
                  << t.members.size() << " members" << std::endl;
        std::cout << "Headquarters: (" << t.headquarters.x << ", "
                  << t.headquarters.y << ")" << std::endl;
        std::cout << "Equal: " << (original == t ? "true" : "false")
                  << std::endl;
      }
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 8: Enum
  // ============================================================================
  std::cout << "--- Example 8: Enum ---" << std::endl;
  {
    Status original = Status::ACTIVE;
    auto bytes_result = fory.serialize(original);
    if (bytes_result.ok()) {
      auto bytes = bytes_result.value();
      print_bytes(bytes);

      auto result = fory.deserialize<Status>(bytes.data(), bytes.size());
      if (result.ok()) {
        std::cout << "Original: " << static_cast<int>(original)
                  << ", Deserialized: " << static_cast<int>(result.value())
                  << std::endl;
        std::cout << "Equal: "
                  << (original == result.value() ? "true" : "false")
                  << std::endl;
      }
    }
  }
  std::cout << std::endl;

  std::cout << "=== All examples completed successfully! ===" << std::endl;

  return 0;
}
