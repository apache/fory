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

import 'package:external_type_test_models/models.dart' as third_party;
import 'package:fory/fory.dart';
import 'package:fory_test/model/external_serializers.dart';
import 'package:test/test.dart';

void _registerUser(Fory fory, {int? id, String? name}) {
  ExternalSerializersForyModule.register(
    fory,
    third_party.User,
    id: id,
    name: name,
  );
}

void _registerGraphTypes(Fory fory) {
  _registerUser(fory, id: 101);
  ExternalSerializersForyModule.register(fory, UserHolder, id: 102);
  ExternalSerializersForyModule.register(fory, third_party.Envelope, id: 103);
  ExternalSerializersForyModule.register(fory, third_party.Node, id: 104);
  ExternalSerializersForyModule.register(fory, third_party.LinkA, id: 105);
  ExternalSerializersForyModule.register(fory, third_party.LinkB, id: 106);
}

void main() {
  group('external structural serializer', () {
    test('round-trips immutable and mutable root targets', () {
      final fory = Fory();
      _registerUser(fory, id: 101);
      ExternalSerializersForyModule.register(fory, third_party.Point, id: 102);
      ExternalSerializersForyModule.register(
        fory,
        third_party.Money,
        name: 'example.Money',
      );
      ExternalSerializersForyModule.register(
        fory,
        third_party.MutableProfile,
        id: 104,
      );
      ExternalSerializersForyModule.register(
        fory,
        third_party.NamedMutableProfile,
        id: 105,
      );

      final user = fory.deserialize<third_party.User>(
        fory.serialize(const third_party.User(name: 'Ada', age: 36)),
      );
      final point = fory.deserialize<third_party.Point>(
        fory.serialize(const third_party.Point(4, 9)),
      );
      final money = fory.deserialize<third_party.Money>(
        fory.serialize(
          const third_party.Money.fromParts(currency: 'USD', units: 42),
        ),
      );
      final mutable =
          third_party.MutableProfile()
            ..name = 'mutable'
            ..score = 7;
      final mutableResult = fory.deserialize<third_party.MutableProfile>(
        fory.serialize(mutable),
      );
      final namedMutable =
          third_party.NamedMutableProfile.empty()
            ..name = 'named'
            ..score = 8;
      final namedMutableResult = fory
          .deserialize<third_party.NamedMutableProfile>(
            fory.serialize(namedMutable),
          );

      expect((user.name, user.age), ('Ada', 36));
      expect((point.x, point.y), (4, 9));
      expect((money.currency, money.units), ('USD', 42));
      expect((mutableResult.name, mutableResult.score), ('mutable', 7));
      expect((namedMutableResult.name, namedMutableResult.score), ('named', 8));
    });

    test('specializes inherited and closed generic members', () {
      final fory = Fory();
      ExternalSerializersForyModule.register(
        fory,
        third_party.DerivedValue,
        id: 101,
      );
      ExternalSerializersForyModule.register(
        fory,
        third_party.Box<String>,
        id: 102,
      );

      final derived = fory.deserialize<third_party.DerivedValue>(
        fory.serialize(third_party.DerivedValue(value: 'base', count: 3)),
      );
      final box = fory.deserialize<third_party.Box<String>>(
        fory.serialize(const third_party.Box<String>('value')),
      );

      expect((derived.value, derived.count), ('base', 3));
      expect(box.value, 'value');
    });

    test('uses existing scalar field annotations', () {
      final fory = Fory();
      ExternalSerializersForyModule.register(
        fory,
        third_party.ScalarValues,
        id: 101,
      );
      final value = third_party.ScalarValues(
        flag: true,
        int8: -8,
        int16: -1600,
        int32: -320000,
        int64: -640000,
        uint8: 8,
        uint16: 1600,
        uint32: 320000,
        uint64: 640000,
        float16: 1.5,
        bfloat16: 2.5,
        float32: 3.5,
        float64: 4.5,
        text: 'scalars',
        timestamp: DateTime.utc(2026, 7, 27, 12, 30),
        duration: Duration(microseconds: 123456),
        binary: Uint8List.fromList(<int>[1, 2, 3]),
      );

      final result = fory.deserialize<third_party.ScalarValues>(
        fory.serialize(value),
      );

      expect(result.flag, isTrue);
      expect(
        (
          result.int8,
          result.int16,
          result.int32,
          result.int64,
          result.uint8,
          result.uint16,
          result.uint32,
          result.uint64,
        ),
        (-8, -1600, -320000, -640000, 8, 1600, 320000, 640000),
      );
      expect(result.float16, closeTo(1.5, 0.01));
      expect(result.bfloat16, closeTo(2.5, 0.02));
      expect(result.float32, closeTo(3.5, 0.0001));
      expect(result.float64, 4.5);
      expect(result.text, 'scalars');
      expect(result.timestamp, DateTime.utc(2026, 7, 27, 12, 30));
      expect(result.duration, const Duration(microseconds: 123456));
      expect(result.binary, <int>[1, 2, 3]);
    });
  });

  group('composition', () {
    test('round-trips direct and recursive carrier fields', () {
      final fory = Fory();
      _registerGraphTypes(fory);
      const ada = third_party.User(name: 'Ada', age: 36);
      const grace = third_party.User(name: 'Grace', age: 37);
      final holder =
          UserHolder()
            ..user = ada
            ..users = <third_party.User>[ada, ada]
            ..userSet = <third_party.User>{grace}
            ..usersByName = <String, third_party.User>{'Ada': ada}
            ..namesByUser = <third_party.User, String>{grace: 'Grace'}
            ..nested = <Map<String, List<third_party.User>>>[
              <String, List<third_party.User>>{
                'users': <third_party.User>[ada, grace],
              },
            ]
            ..value = grace
            ..values = <Object?>[ada, 'text', 7, null];

      final result = fory.deserialize<UserHolder>(fory.serialize(holder));

      expect((result.user!.name, result.user!.age), ('Ada', 36));
      expect(result.users.map((user) => user.name), ['Ada', 'Ada']);
      expect(identical(result.users[0], result.users[1]), isTrue);
      expect(result.userSet.single.name, 'Grace');
      expect(result.usersByName['Ada']!.age, 36);
      expect(result.namesByUser.keys.single.name, 'Grace');
      expect(result.nested.single['users']!.map((user) => user.name), [
        'Ada',
        'Grace',
      ]);
      expect((result.value as third_party.User).name, 'Grace');
      expect((result.values.first as third_party.User).name, 'Ada');
      expect(result.values.sublist(1), <Object?>['text', 7, null]);
    });

    test('round-trips external target carrier fields', () {
      final fory = Fory();
      _registerGraphTypes(fory);
      const user = third_party.User(name: 'Ada', age: 36);
      final envelope =
          third_party.Envelope()
            ..user = user
            ..users = <third_party.User>[user]
            ..userSet = <third_party.User>{user}
            ..usersByName = <String, third_party.User>{'Ada': user}
            ..namesByUser = <third_party.User, String>{user: 'Ada'}
            ..nested = <Map<String, List<third_party.User>>>[
              <String, List<third_party.User>>{
                'users': <third_party.User>[user],
              },
            ]
            ..value = user
            ..values = <Object?>[user, 'value'];

      final result = fory.deserialize<third_party.Envelope>(
        fory.serialize(envelope),
      );

      expect(result.user!.name, 'Ada');
      expect(result.users.single.age, 36);
      expect(result.userSet.single.name, 'Ada');
      expect(result.usersByName['Ada']!.age, 36);
      expect(result.namesByUser.keys.single.name, 'Ada');
      expect(result.nested.single['users']!.single.name, 'Ada');
      expect((result.value as third_party.User).name, 'Ada');
      expect((result.values.first as third_party.User).name, 'Ada');
    });

    test('round-trips non-empty and empty root carriers', () {
      final fory = Fory();
      _registerUser(fory, id: 101);
      const user = third_party.User(name: 'Ada', age: 36);

      final users =
          fory.deserialize<Object?>(fory.serialize(<third_party.User>[user]))
              as List<Object?>;
      final userSet =
          fory.deserialize<Object?>(fory.serialize(<third_party.User>{user}))
              as Set<Object?>;
      final byName =
          fory.deserialize<Object?>(
                fory.serialize(<String, third_party.User>{'Ada': user}),
              )
              as Map<Object?, Object?>;
      final byUser =
          fory.deserialize<Object?>(
                fory.serialize(<third_party.User, String>{user: 'Ada'}),
              )
              as Map<Object?, Object?>;

      expect((users.single as third_party.User).name, 'Ada');
      expect((userSet.single as third_party.User).age, 36);
      expect((byName['Ada'] as third_party.User).age, 36);
      expect((byUser.keys.single as third_party.User).name, 'Ada');
      expect(
        fory.deserialize<Object?>(fory.serialize(<third_party.User>[])),
        isEmpty,
      );
      expect(
        fory.deserialize<Object?>(fory.serialize(<third_party.User>{})),
        isEmpty,
      );
      expect(
        fory.deserialize<Object?>(fory.serialize(<String, third_party.User>{})),
        isEmpty,
      );
    });
  });

  group('references and compatibility', () {
    test('preserves mutable self and indirect cycles', () {
      final fory = Fory();
      _registerGraphTypes(fory);
      final node = third_party.Node.empty()..label = 'self';
      node.next = node;
      final a = third_party.LinkA()..label = 'a';
      final b = third_party.LinkB()..label = 'b';
      a.link = b;
      b.link = a;

      final nodeResult = fory.deserialize<third_party.Node>(
        fory.serialize(node),
      );
      final linkResult = fory.deserialize<third_party.LinkA>(fory.serialize(a));

      expect(identical(nodeResult, nodeResult.next), isTrue);
      expect(linkResult.link!.label, 'b');
      expect(identical(linkResult, linkResult.link!.link), isTrue);
    });

    test(
      'reads added, removed, reordered, nullable, and renamed-id fields',
      () {
        final version1Writer = Fory(compatible: true);
        final version2Reader = Fory(compatible: true);
        ExternalSerializersForyModule.register(
          version1Writer,
          third_party.ContactVersion1,
          name: 'example.Contact',
        );
        ExternalSerializersForyModule.register(
          version2Reader,
          third_party.ContactVersion2,
          name: 'example.Contact',
        );

        final version2 = version2Reader
            .deserialize<third_party.ContactVersion2>(
              version1Writer.serialize(
                const third_party.ContactVersion1(
                  firstName: 'Ada',
                  nickname: null,
                ),
              ),
            );

        expect(version2.fullName, 'Ada');
        expect(version2.nickname, isNull);
        expect(version2.email, isNull);

        final version2Writer = Fory(compatible: true);
        final version1Reader = Fory(compatible: true);
        ExternalSerializersForyModule.register(
          version2Writer,
          third_party.ContactVersion2,
          name: 'example.Contact',
        );
        ExternalSerializersForyModule.register(
          version1Reader,
          third_party.ContactVersion1,
          name: 'example.Contact',
        );
        final version1 = version1Reader
            .deserialize<third_party.ContactVersion1>(
              version2Writer.serialize(
                const third_party.ContactVersion2(
                  fullName: 'Grace',
                  nickname: 'Amazing',
                  email: 'grace@example.test',
                ),
              ),
            );

        expect(version1.firstName, 'Grace');
        expect(version1.nickname, 'Amazing');
      },
    );
  });

  test('matches equivalent ordinary generated bytes', () {
    final externalFory = Fory();
    final ordinaryFory = Fory();
    ExternalSerializersForyModule.register(
      externalFory,
      third_party.EquivalentUser,
      name: 'example.EquivalentUser',
    );
    ExternalSerializersForyModule.register(
      ordinaryFory,
      OrdinaryEquivalentUser,
      name: 'example.EquivalentUser',
    );

    final externalBytes = externalFory.serialize(
      const third_party.EquivalentUser(name: 'Ada', age: 36),
    );
    final ordinaryBytes = ordinaryFory.serialize(
      const OrdinaryEquivalentUser(name: 'Ada', age: 36),
    );

    expect(externalBytes, ordinaryBytes);
    final external = externalFory.deserialize<third_party.EquivalentUser>(
      externalBytes,
    );
    final ordinary = ordinaryFory.deserialize<OrdinaryEquivalentUser>(
      ordinaryBytes,
    );
    expect((external.name, external.age), (ordinary.name, ordinary.age));
  });
}
