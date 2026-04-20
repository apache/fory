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
import 'package:fory_test/model/person.dart';
import 'package:test/test.dart';

// Consumer-side integration coverage for build_runner output. These tests prove
// that generated library namespaces register directly against the public
// package:fory API; the Java-driven xlang harness covers cross-runtime wire
// compatibility separately.
void main() {
  group('generated registration', () {
    test('round-trips struct and enum data', () {
      final fory = Fory();
      PersonFory.register(
        fory,
        Color,
        namespace: 'fory_test.person',
        typeName: 'Color',
      );
      PersonFory.register(
        fory,
        Person,
        namespace: 'fory_test.person',
        typeName: 'Person',
      );

      final person = Person()
        ..name = 'Ada'
        ..age = Int32(36)
        ..favoriteColor = Color.blue
        ..tags = <String?>['engineer', null]
        ..scores = <String, Int32>{
          'math': Int32(100),
          'logic': Int32(99),
        };

      final bytes = fory.serialize(person);
      final roundTrip = fory.deserialize<Person>(bytes);

      expect(roundTrip.name, equals('Ada'));
      expect(roundTrip.age, equals(Int32(36)));
      expect(roundTrip.favoriteColor, equals(Color.blue));
      expect(roundTrip.tags, equals(<String?>['engineer', null]));
      expect(roundTrip.scores['math'], equals(Int32(100)));
      expect(roundTrip.scores['logic'], equals(Int32(99)));
    });

    test('supports root trackRef for top-level graphs', () {
      final fory = Fory();
      PersonFory.register(
        fory,
        RefNode,
        namespace: 'fory_test.person',
        typeName: 'RefNode',
      );

      // `trackRef` is the root-level escape hatch for graphs without field
      // metadata, so this top-level list verifies shared identity at the root.
      final node = RefNode()..name = 'root';
      final bytes = fory.serialize(<Object?>[node, node], trackRef: true);
      final roundTrip = fory.deserialize<Object?>(bytes) as List<Object?>;

      expect(roundTrip, hasLength(2));
      expect(identical(roundTrip[0], roundTrip[1]), isTrue);
    });

    test('preserves self reference on annotated ref fields', () {
      final fory = Fory();
      PersonFory.register(
        fory,
        RefNode,
        namespace: 'fory_test.person',
        typeName: 'RefNode',
      );

      final node = RefNode()
        ..name = 'self'
        ..self = null;
      node.self = node;

      final bytes = fory.serialize(node);
      final roundTrip = fory.deserialize<RefNode>(bytes);
      expect(identical(roundTrip, roundTrip.self), isTrue);
    });

    test('fixed payload stays smaller than evolving payload in compatible mode',
        () {
      final fory = Fory(compatible: true);
      PersonFory.register(
        fory,
        EvolvingPayload,
        namespace: 'fory_test.person',
        typeName: 'EvolvingPayload',
      );
      PersonFory.register(
        fory,
        FixedPayload,
        namespace: 'fory_test.person',
        typeName: 'FixedPayload',
      );

      final evolving = EvolvingPayload()..value = 'payload';
      final fixed = FixedPayload()..value = 'payload';

      final evolvingBytes = fory.serialize(evolving);
      final fixedBytes = fory.serialize(fixed);

      expect(fixedBytes.length, lessThan(evolvingBytes.length));
      expect(fory.deserialize<EvolvingPayload>(evolvingBytes).value,
          equals('payload'));
      expect(
          fory.deserialize<FixedPayload>(fixedBytes).value, equals('payload'));
    });

    test('serializes private mutable fields', () {
      final fory = Fory();
      PersonFory.register(
        fory,
        PrivatePayload,
        namespace: 'fory_test.person',
        typeName: 'PrivatePayload',
      );

      final mutable = PrivatePayload()..updateSecret('hidden');
      final bytes = fory.serialize(mutable);
      final roundTrip = fory.deserialize<PrivatePayload>(bytes);

      expect(bytes, isNotEmpty);
      expect(roundTrip.secret, equals('hidden'));
    });

    test('serializes private immutable fields', () {
      final fory = Fory();
      PersonFory.register(
        fory,
        PrivateImmutablePayload,
        namespace: 'fory_test.person',
        typeName: 'PrivateImmutablePayload',
      );

      final immutable = PrivateImmutablePayload('sealed');
      final bytes = fory.serialize(immutable);
      final roundTrip = fory.deserialize<PrivateImmutablePayload>(bytes);

      expect(bytes, isNotEmpty);
      expect(roundTrip.secret, equals('sealed'));
    });

    test('supports per-type registration with custom ids', () {
      final fory = Fory();
      PersonFory.register(fory, Color, id: 101);
      PersonFory.register(fory, Person, id: 102);

      final person = Person()
        ..name = 'Ada'
        ..age = Int32(36)
        ..favoriteColor = Color.blue;

      final bytes = fory.serialize(person);
      final roundTrip = fory.deserialize<Person>(bytes);

      expect(roundTrip.name, equals('Ada'));
      expect(roundTrip.age, equals(Int32(36)));
      expect(roundTrip.favoriteColor, equals(Color.blue));
    });
  });
}
