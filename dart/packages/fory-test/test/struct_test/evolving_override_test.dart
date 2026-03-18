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

library;

import 'package:fory/fory.dart';
import 'package:fory_test/entity/xlang_test_models.dart';
import 'package:test/test.dart';

void main() {
  group('Struct evolving override', () {
    test('fixed struct payload is smaller in compatible mode', () {
      final fory = Fory(compatible: true);
      registerXlangStruct(fory, EvolvingOverrideStruct, typeId: 1001);
      registerXlangStruct(fory, FixedOverrideStruct, typeId: 1002);

      final evolving = EvolvingOverrideStruct()..f1 = 'payload';
      final fixed = FixedOverrideStruct()..f1 = 'payload';

      final evolvingBytes = fory.serialize(evolving);
      final fixedBytes = fory.serialize(fixed);

      expect(fixedBytes.length, lessThan(evolvingBytes.length));
      expect((fory.deserialize(evolvingBytes) as EvolvingOverrideStruct).f1,
          equals('payload'));
      expect((fory.deserialize(fixedBytes) as FixedOverrideStruct).f1,
          equals('payload'));
    });
  });
}
