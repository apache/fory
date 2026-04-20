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
  group('TypeOption', () {
    test('RefOption defaults tracked to true', () {
      const ref = RefOption();
      expect(ref.tracked, isTrue);
    });

    test('RefOption accepts explicit false', () {
      const ref = RefOption(false);
      expect(ref.tracked, isFalse);
    });

    test('NullableOption defaults value to true', () {
      const nullable = NullableOption();
      expect(nullable.value, isTrue);
    });

    test('NullableOption accepts explicit false', () {
      const nullable = NullableOption(false);
      expect(nullable.value, isFalse);
    });

    test('TypeOption.ref factory creates RefOption', () {
      const option = TypeOption.ref();
      expect(option, isA<RefOption>());
      expect((option as RefOption).tracked, isTrue);
    });

    test('TypeOption.nullable factory creates NullableOption', () {
      const option = TypeOption.nullable();
      expect(option, isA<NullableOption>());
      expect((option as NullableOption).value, isTrue);
    });
  });

  group('ValueType', () {
    test('default constructor has empty options', () {
      const vt = ValueType();
      expect(vt.options, isEmpty);
    });

    test('ValueType.ref() sets ref option', () {
      const vt = ValueType.ref();
      expect(vt.options, hasLength(1));
      expect(vt.options.first, isA<RefOption>());
      expect((vt.options.first as RefOption).tracked, isTrue);
    });

    test('ValueType.noRef() sets ref(false)', () {
      const vt = ValueType.noRef();
      expect(vt.options, hasLength(1));
      expect((vt.options.first as RefOption).tracked, isFalse);
    });

    test('ValueType.nullable() sets nullable option', () {
      const vt = ValueType.nullable();
      expect(vt.options, hasLength(1));
      expect((vt.options.first as NullableOption).value, isTrue);
    });

    test('ValueType.nonNullable() sets nullable(false)', () {
      const vt = ValueType.nonNullable();
      expect(vt.options, hasLength(1));
      expect((vt.options.first as NullableOption).value, isFalse);
    });

    test('ValueType.refNullable() sets both ref and nullable', () {
      const vt = ValueType.refNullable();
      expect(vt.options, hasLength(2));
      expect(vt.options[0], isA<RefOption>());
      expect(vt.options[1], isA<NullableOption>());
    });
  });

  group('ListType', () {
    test('default constructor has empty options and default element', () {
      const lt = ListType();
      expect(lt.options, isEmpty);
      expect(lt.element, isA<ValueType>());
      expect(lt.element.options, isEmpty);
    });

    test('ListType.ref() sets ref option', () {
      const lt = ListType.ref();
      expect(lt.options, hasLength(1));
      expect((lt.options.first as RefOption).tracked, isTrue);
    });

    test('ListType.noRef() sets ref(false)', () {
      const lt = ListType.noRef();
      expect(lt.options, hasLength(1));
      expect((lt.options.first as RefOption).tracked, isFalse);
    });

    test('ListType.nullable() sets nullable option', () {
      const lt = ListType.nullable();
      expect(lt.options, hasLength(1));
      expect((lt.options.first as NullableOption).value, isTrue);
    });

    test('accepts custom element TypeSpec', () {
      const lt = ListType(element: ValueType.ref());
      expect(lt.element.options, hasLength(1));
      expect((lt.element.options.first as RefOption).tracked, isTrue);
    });

    test('nested MapType element', () {
      const lt = ListType(
        options: [TypeOption.ref()],
        element: MapType(
          value: ValueType.ref(),
        ),
      );
      expect(lt.options, hasLength(1));
      final mapElement = lt.element as MapType;
      expect(mapElement.value.options, hasLength(1));
      expect((mapElement.value.options.first as RefOption).tracked, isTrue);
    });
  });

  group('MapType', () {
    test('default constructor has empty options and default key/value', () {
      const mt = MapType();
      expect(mt.options, isEmpty);
      expect(mt.key, isA<ValueType>());
      expect(mt.value, isA<ValueType>());
    });

    test('MapType.ref() sets ref option', () {
      const mt = MapType.ref();
      expect(mt.options, hasLength(1));
      expect((mt.options.first as RefOption).tracked, isTrue);
    });

    test('MapType.noRef() sets ref(false)', () {
      const mt = MapType.noRef();
      expect(mt.options, hasLength(1));
      expect((mt.options.first as RefOption).tracked, isFalse);
    });

    test('MapType.nullable() sets nullable option', () {
      const mt = MapType.nullable();
      expect(mt.options, hasLength(1));
      expect((mt.options.first as NullableOption).value, isTrue);
    });

    test('accepts custom key and value TypeSpecs', () {
      const mt = MapType(
        key: ValueType.ref(),
        value: ValueType.refNullable(),
      );
      expect(mt.key.options, hasLength(1));
      expect(mt.value.options, hasLength(2));
    });
  });
}
