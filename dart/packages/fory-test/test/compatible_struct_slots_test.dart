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

import 'package:fory/fory.dart';
import 'package:fory_test/entity/xlang_test_models.dart';
import 'package:test/test.dart';

void main() {
  test('compatible named struct round trip scopes nested struct slots', () {
    final fory = Fory(compatible: true);
    registerXlangType(
      fory,
      Color,
      namespace: 'demo',
      typeName: 'color',
    );
    registerXlangType(
      fory,
      Item,
      namespace: 'demo',
      typeName: 'item',
    );
    registerXlangType(
      fory,
      SimpleStruct,
      namespace: 'demo',
      typeName: 'simple_struct',
    );

    final original = SimpleStruct()
      ..f2 = Int32(1)
      ..f7 = Int32(2)
      ..f8 = Int32(3)
      ..last = Int32(4)
      ..f4 = 'outer'
      ..f6 = <String>['a', 'b']
      ..f1 = <Int32?, double?>{Int32(7): 9.5}
      ..f3 = (Item()..name = 'inner')
      ..f5 = Color.blue;

    final firstBytes = fory.serialize(original);
    final decoded = fory.deserialize<SimpleStruct>(firstBytes);

    expect(() => fory.serialize(decoded), returnsNormally);

    final secondBytes = fory.serialize(decoded);
    final roundTrip = fory.deserialize<SimpleStruct>(secondBytes);

    expect(roundTrip.f2, equals(Int32(1)));
    expect(roundTrip.f7, equals(Int32(2)));
    expect(roundTrip.f8, equals(Int32(3)));
    expect(roundTrip.last, equals(Int32(4)));
    expect(roundTrip.f4, equals('outer'));
    expect(roundTrip.f6, equals(<String>['a', 'b']));
    expect(roundTrip.f1[Int32(7)], equals(9.5));
    expect(roundTrip.f3.name, equals('inner'));
    expect(roundTrip.f5, equals(Color.blue));
  });
}
