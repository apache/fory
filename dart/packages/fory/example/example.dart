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

import 'dart:typed_data';

import 'package:fory/fory.dart';

part 'example.g.dart';

@foryClass
class Person {
  final String firstName, lastName;
  final int age;
  final LocalDate dateOfBirth;

  const Person(this.firstName, this.lastName, this.age, this.dateOfBirth);
}

void main() {
  Fory fory = Fory(ref: true);
  fory.register(Person, typename: "example.Person");
  Person obj = Person('Leo', 'Leo', 21, LocalDate(2004, 1, 1));

  // Serialize
  Uint8List bytes = fory.serialize(obj);

  // Deserialize
  obj = fory.deserialize(bytes) as Person;
}
