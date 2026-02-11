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
import 'package:fory_test/entity/complex_obj_2.dart';
import 'package:test/test.dart';

void main() {
  group('Struct codegen for ComplexObject2', () {
    test('generates TypeSpec for ComplexObject2', () async {
      TypeSpec spec = TypeSpec(
        ComplexObject2,
        true,
        false,
        [
          FieldSpec(
            'f1',
            FieldTypeSpec(
              Object,
              ObjType.UNKNOWN,
              false,
              false,
              null,
              const [],
            ),
            true,
            true,
            (Object inst) => (inst as ComplexObject2).f1,
            null,
          ),
          FieldSpec(
            'f2',
            FieldTypeSpec(
              Map,
              ObjType.MAP,
              false,
              false,
              null,
              const [
                FieldTypeSpec(
                  Int8,
                  ObjType.INT8,
                  false,
                  true,
                  null,
                  [],
                ),
                FieldTypeSpec(
                  Int32,
                  ObjType.INT32,
                  false,
                  true,
                  null,
                  [],
                ),
              ],
            ),
            true,
            true,
            (Object inst) => (inst as ComplexObject2).f2,
            null,
          ),
        ],
        (List<dynamic> objList) => ComplexObject2(
          (objList[0] as Object),
          Map<Int8, Int32>.of((objList[1] as Map)
              .map((k, v) => MapEntry((k as Int8), (v as Int32)))),
        ),
        null,
      );
      check($ComplexObject2).equals(spec);
    });
  });
}
