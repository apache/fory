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

// @Skip()
library;

import 'package:checks/checks.dart';
import 'package:fory/fory.dart';
import 'package:fory_test/entity/enum_foo.dart';
import 'package:test/test.dart';

void main() {
  group('Simple Enum Code Generation', () {
    test('test enum spec generation', () async {
      EnumSpec enumSpec = EnumSpec(EnumFoo, [EnumFoo.A, EnumFoo.B]);
      EnumSpec enumSubTypeSpec =
          EnumSpec(EnumSubClass, [EnumSubClass.A, EnumSubClass.B]);
      check($EnumFoo).equals(enumSpec);
      check($EnumSubClass).equals(enumSubTypeSpec);
    });
  });
}
