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
 * Fory C++ Row Format Example
 *
 * This example demonstrates how to use Fory's row format for
 * cache-friendly binary random access. Row format is ideal for
 * scenarios where you need:
 * - Partial serialization/deserialization
 * - Random field access without full deserialization
 * - Interoperability with columnar formats
 */

#include <cstdint>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "fory/encoder/row_encode_trait.h"
#include "fory/encoder/row_encoder.h"
#include "fory/row/row.h"
#include "fory/row/schema.h"
#include "fory/row/writer.h"

// Define a struct for the encoder example
struct Employee {
  std::string name;
  int32_t id;
  float salary;
  FORY_STRUCT(Employee, name, id, salary);
};

// Define a nested struct
struct Department {
  std::string dept_name;
  Employee manager;
  std::vector<Employee> employees;
  FORY_STRUCT(Department, dept_name, manager, employees);
};

int main() {
  std::cout << "=== Fory C++ Row Format Example ===" << std::endl << std::endl;

  // ============================================================================
  // Example 1: Manual Row Writing with Schema
  // ============================================================================
  std::cout << "--- Example 1: Manual Row Writing ---" << std::endl;
  {
    using namespace fory::row;

    // Define schema with fields
    auto name_field = field("name", utf8());
    auto age_field = field("age", int32());
    auto score_field = field("score", float32());
    auto tags_field = field("tags", list(utf8()));

    std::vector<FieldPtr> fields = {name_field, age_field, score_field,
                                    tags_field};
    auto row_schema = schema(fields);

    // Create a row writer
    RowWriter writer(row_schema);
    writer.Reset();

    // Write primitive fields
    writer.WriteString(0, "Alice");
    writer.Write(1, static_cast<int32_t>(25));
    writer.Write(2, 95.5f);

    // Write array field
    writer.SetNotNullAt(3);
    int array_start = writer.cursor();
    auto list_type = std::dynamic_pointer_cast<ListType>(utf8());
    ArrayWriter array_writer(list(utf8()), &writer);
    array_writer.Reset(3);
    array_writer.WriteString(0, "developer");
    array_writer.WriteString(1, "team-lead");
    array_writer.WriteString(2, "mentor");
    writer.SetOffsetAndSize(3, array_start, writer.cursor() - array_start);

    // Convert to row and read back
    auto row = writer.ToRow();

    std::cout << "Written row:" << std::endl;
    std::cout << "  name: " << row->GetString(0) << std::endl;
    std::cout << "  age: " << row->GetInt32(1) << std::endl;
    std::cout << "  score: " << row->GetFloat(2) << std::endl;

    auto tags_array = row->GetArray(3);
    std::cout << "  tags: [";
    for (int i = 0; i < tags_array->num_elements(); ++i) {
      std::cout << tags_array->GetString(i);
      if (i < tags_array->num_elements() - 1)
        std::cout << ", ";
    }
    std::cout << "]" << std::endl;

    // Print full row string representation
    std::cout << "  Full row: " << row->ToString() << std::endl;
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 2: Using RowEncoder for Automatic Encoding
  // ============================================================================
  std::cout << "--- Example 2: Automatic Encoding with RowEncoder ---"
            << std::endl;
  {
    using namespace fory::row;

    // Create an employee
    Employee emp{"Bob", 1001, 75000.0f};

    // Create encoder and encode
    encoder::RowEncoder<Employee> enc;
    enc.Encode(emp);

    // Get the schema (automatically generated from struct)
    auto &schema = enc.GetSchema();
    std::cout << "Generated schema fields: ";
    for (const auto &name : schema.field_names()) {
      std::cout << name << " ";
    }
    std::cout << std::endl;

    // Read back from encoded row
    auto row = enc.GetWriter().ToRow();
    std::cout << "Encoded employee:" << std::endl;
    std::cout << "  name: " << row->GetString(0) << std::endl;
    std::cout << "  id: " << row->GetInt32(1) << std::endl;
    std::cout << "  salary: " << row->GetFloat(2) << std::endl;
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 3: Nested Struct Encoding
  // ============================================================================
  std::cout << "--- Example 3: Nested Struct Encoding ---" << std::endl;
  {
    using namespace fory::row;

    // Create a department with nested employees
    Department dept{"Engineering",
                    {"Alice", 1000, 120000.0f}, // manager
                    {
                        {"Bob", 1001, 75000.0f},
                        {"Carol", 1002, 80000.0f},
                        {"Dave", 1003, 70000.0f},
                    }};

    // Encode using RowEncoder
    encoder::RowEncoder<Department> enc;
    enc.Encode(dept);

    auto &schema = enc.GetSchema();
    std::cout << "Department schema fields: ";
    for (const auto &name : schema.field_names()) {
      std::cout << name << " ";
    }
    std::cout << std::endl;

    // Read back
    auto row = enc.GetWriter().ToRow();
    std::cout << "Encoded department:" << std::endl;
    std::cout << "  dept_name: " << row->GetString(0) << std::endl;

    // Access nested manager struct
    auto manager_row = row->GetStruct(1);
    std::cout << "  manager: {name=" << manager_row->GetString(0)
              << ", id=" << manager_row->GetInt32(1)
              << ", salary=" << manager_row->GetFloat(2) << "}" << std::endl;

    // Access employees array
    auto employees_array = row->GetArray(2);
    std::cout << "  employees (" << employees_array->num_elements()
              << " total):" << std::endl;
    for (int i = 0; i < employees_array->num_elements(); ++i) {
      auto emp_row = employees_array->GetStruct(i);
      std::cout << "    - {name=" << emp_row->GetString(0)
                << ", id=" << emp_row->GetInt32(1)
                << ", salary=" << emp_row->GetFloat(2) << "}" << std::endl;
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 4: Array Encoding
  // ============================================================================
  std::cout << "--- Example 4: Array Encoding ---" << std::endl;
  {
    using namespace fory::row;

    // Encode a vector of employees
    std::vector<Employee> employees = {
        {"Eve", 2001, 65000.0f},
        {"Frank", 2002, 68000.0f},
    };

    encoder::RowEncoder<decltype(employees)> enc;
    enc.Encode(employees);

    auto &type = enc.GetType();
    std::cout << "Type name: " << type.name() << std::endl;

    // Get array data
    auto array_data = enc.GetWriter().CopyToArrayData();
    std::cout << "Encoded " << array_data->num_elements()
              << " employees:" << std::endl;

    for (int i = 0; i < array_data->num_elements(); ++i) {
      auto emp_row = array_data->GetStruct(i);
      std::cout << "  [" << i << "] {name=" << emp_row->GetString(0)
                << ", id=" << emp_row->GetInt32(1)
                << ", salary=" << emp_row->GetFloat(2) << "}" << std::endl;
    }
  }
  std::cout << std::endl;

  // ============================================================================
  // Example 5: Creating Arrays from Vectors
  // ============================================================================
  std::cout << "--- Example 5: Direct Array Creation ---" << std::endl;
  {
    using namespace fory::row;

    // Create array directly from vector
    std::vector<int32_t> numbers = {10, 20, 30, 40, 50};
    auto array = ArrayData::From(numbers);

    std::cout << "Created array with " << array->num_elements()
              << " elements:" << std::endl;
    std::cout << "  " << array->ToString() << std::endl;
  }
  std::cout << std::endl;

  std::cout << "=== All examples completed successfully! ===" << std::endl;

  return 0;
}
