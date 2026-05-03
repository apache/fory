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
import 'package:test/test.dart';

void main() {
  group('scalar type specs', () {
    test('DeclaredType stores leaf overrides without changing leaf kind', () {
      const declared = DeclaredType(nullable: true, ref: true, dynamic: false);
      expect(declared.nullable, isTrue);
      expect(declared.ref, isTrue);
      expect(declared.dynamic, isFalse);
    });

    test('Int32Type keeps encoding and node-local overrides separate', () {
      const spec = Int32Type(
        encoding: Encoding.fixed,
        nullable: true,
        ref: false,
        dynamic: false,
      );
      expect(spec.encoding, equals(Encoding.fixed));
      expect(spec.nullable, isTrue);
      expect(spec.ref, isFalse);
      expect(spec.dynamic, isFalse);
    });

    test('Uint64Type supports tagged encoding', () {
      const spec = Uint64Type(encoding: Encoding.tagged);
      expect(spec.encoding, equals(Encoding.tagged));
      expect(spec.nullable, isNull);
      expect(spec.ref, isNull);
      expect(spec.dynamic, isNull);
    });
  });

  group('container type specs', () {
    test('ListType stores nested element specs', () {
      const lt = ListType(
        element: MapType(
          value: DeclaredType(ref: true),
        ),
        nullable: true,
      );
      expect(lt.nullable, isTrue);
      final mapElement = lt.element as MapType;
      final declaredValue = mapElement.value as DeclaredType;
      expect(declaredValue.ref, isTrue);
    });

    test('ArrayType stores dense element specs', () {
      const array = ArrayType(
        element: Int32Type(),
        nullable: true,
      );
      expect(array.nullable, isTrue);
      expect(array.element, isA<Int32Type>());
    });

    test('MapType stores explicit key and value trees', () {
      const mt = MapType(
        key: StringType(),
        value: ListType(
          element: Int32Type(
            nullable: true,
            encoding: Encoding.fixed,
          ),
        ),
        ref: true,
      );
      expect(mt.ref, isTrue);
      expect(mt.key, isA<StringType>());
      final value = mt.value as ListType;
      final element = value.element as Int32Type;
      expect(element.nullable, isTrue);
      expect(element.encoding, equals(Encoding.fixed));
    });
  });

  group('field annotations', () {
    test('ForyField stores canonical root type overrides', () {
      const field = ForyField(
        id: 3,
        nullable: true,
        ref: false,
        dynamic: true,
        type: MapType(
          key: StringType(),
          value: ListType(
            element: Int32Type(encoding: Encoding.fixed),
          ),
        ),
      );
      expect(field.id, equals(3));
      expect(field.nullable, isTrue);
      expect(field.dynamic, isTrue);
      expect(field.type, isA<MapType>());
    });

    test('container sugar stores nested element and value overrides', () {
      const listField = ListField(
        element: DeclaredType(ref: true),
      );
      const arrayField = ArrayField(
        element: Uint8Type(),
      );
      const mapField = MapField(
        value: ListType(
          element: Int32Type(encoding: Encoding.fixed),
        ),
      );
      expect((listField.element as DeclaredType).ref, isTrue);
      expect(arrayField.element, isA<Uint8Type>());
      final nestedList = mapField.value as ListType;
      final nestedElement = nestedList.element as Int32Type;
      expect(nestedElement.encoding, equals(Encoding.fixed));
    });
  });
}
