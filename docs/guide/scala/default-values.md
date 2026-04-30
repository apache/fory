---
title: Default Values
sidebar_position: 3
id: default_values
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Fory supports Scala class default values during deserialization when using compatible mode. This feature enables forward/backward compatibility when case classes or regular Scala classes have default parameters.

## Overview

When a Scala class has default parameters, the Scala compiler generates methods in the companion object (for case classes) or in the class itself (for regular Scala classes) like `apply$default$1`, `apply$default$2`, etc. that return the default values. Fory can detect these methods and use them when deserializing objects where certain fields are missing from the serialized data.

## Supported Class Types

Fory supports default values for:

- **Case classes** with default parameters
- **Regular Scala classes** with default parameters in their primary constructor
- **Nested case classes** with default parameters

## How It Works

1. **Detection**: Fory detects if a class is a Scala class by checking for the presence of default value methods (`apply$default$N` or `$default$N`).

2. **Default Value Discovery**:
   - For case classes: Fory scans the companion object for methods named `apply$default$1`, `apply$default$2`, etc.
   - For regular Scala classes: Fory scans the class itself for methods named `$default$1`, `$default$2`, etc.

3. **Field Mapping**: During deserialization, Fory identifies fields that exist in the target class but are missing from the serialized data.

4. **Value Application**: After reading all available fields from the serialized data, Fory applies default values to any missing fields.

## Usage

This feature is automatically enabled when:

- Compatible mode is enabled (`withCompatible(true)`)
- The target class is detected as a Scala class with default values
- A field is missing from the serialized data but exists in the target class

No additional configuration is required.

## Examples

### Case Class with Default Values

```scala
import org.apache.fory.Fory
import org.apache.fory.serializer.scala.ScalaSerializers

// Class WITHOUT default values (for serialization)
case class PersonV1(name: String)

// Class WITH default values (for deserialization)
case class PersonV2(name: String, age: Int = 25, city: String = "Unknown")

val fory = Fory.builder()
  .withCompatible(true)
  .withScalaOptimizationEnabled(true)
  .build()

ScalaSerializers.registerSerializers(fory)

// Serialize using class without default values
val original = PersonV1("John")
val serialized = fory.serialize(original)

// Deserialize into class with default values
// Missing fields will use defaults
val deserialized = fory.deserialize(serialized).asInstanceOf[PersonV2]
// deserialized.name == "John"
// deserialized.age == 25 (default)
// deserialized.city == "Unknown" (default)
```

### Regular Scala Class with Default Values

```scala
// Class WITHOUT default values (for serialization)
class EmployeeV1(val name: String)

// Class WITH default values (for deserialization)
class EmployeeV2(
  val name: String,
  val age: Int = 30,
  val department: String = "Engineering"
)

val fory = Fory.builder()
  .withCompatible(true)
  .withScalaOptimizationEnabled(true)
  .build()

ScalaSerializers.registerSerializers(fory)

// Serialize using class without default values
val original = new EmployeeV1("Jane")
val serialized = fory.serialize(original)

// Deserialize into class with default values
val deserialized = fory.deserialize(serialized).asInstanceOf[EmployeeV2]
// deserialized.name == "Jane"
// deserialized.age == 30 (default)
// deserialized.department == "Engineering" (default)
```

### Complex Default Values

Default values can be complex expressions:

```scala
// Class WITHOUT default values (for serialization)
case class ConfigV1(name: String)

// Class WITH default values (for deserialization)
case class ConfigV2(
  name: String,
  settings: Map[String, String] = Map("default" -> "value"),
  tags: List[String] = List("default"),
  enabled: Boolean = true
)

val fory = Fory.builder()
  .withCompatible(true)
  .withScalaOptimizationEnabled(true)
  .build()

ScalaSerializers.registerSerializers(fory)

val original = ConfigV1("myConfig")
val serialized = fory.serialize(original)

val deserialized = fory.deserialize(serialized).asInstanceOf[ConfigV2]
// deserialized.name == "myConfig"
// deserialized.settings == Map("default" -> "value")
// deserialized.tags == List("default")
// deserialized.enabled == true
```

### Nested Case Classes

```scala
object Models {
  // Class WITHOUT default values (for serialization)
  case class PersonV1(name: String)

  // Classes WITH default values (for deserialization)
  case class Address(street: String, city: String = "DefaultCity")
  case class PersonV2(name: String, address: Address = Address("DefaultStreet"))
}

val fory = Fory.builder()
  .withCompatible(true)
  .withScalaOptimizationEnabled(true)
  .build()

ScalaSerializers.registerSerializers(fory)

val original = Models.PersonV1("Alice")
val serialized = fory.serialize(original)

val deserialized = fory.deserialize(serialized).asInstanceOf[Models.PersonV2]
// deserialized.name == "Alice"
// deserialized.address == Address("DefaultStreet", "DefaultCity")
```

## Related Topics

- [Schema Evolution](../java/schema-evolution.md) - Forward/backward compatibility in Java
- [Fory Creation](fory-creation.md) - Setting up Fory with compatible mode
