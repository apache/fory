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

import 'dart:convert';

import 'package:fory/fory.dart';
import 'package:test/test.dart';

part 'collection_serializer_test.fory.dart';

final Int64 _int64Min = Int64.parseHex('8000000000000000');
final Int64 _int64Max = Int64.parseHex('7fffffffffffffff');
final Uint64 _uint64HighBit = Uint64.parseHex('8000000000000000');
final Uint64 _uint64Max = Uint64.parseHex('ffffffffffffffff');

@ForyStruct()
class NumericContainerEnvelope {
  NumericContainerEnvelope();

  List<Int64> int64s = <Int64>[];
  List<Uint64> uint64s = <Uint64>[];
  Map<String, Int64> int64ByName = <String, Int64>{};
  Map<String, Uint64> uint64ByName = <String, Uint64>{};
}

void _registerNumericContainerEnvelope(Fory fory) {
  CollectionSerializerTestFory.register(
    fory,
    NumericContainerEnvelope,
    namespace: 'test',
    typeName: 'NumericContainerEnvelope',
  );
}

NumericContainerEnvelope _numericContainerEnvelope() {
  return NumericContainerEnvelope()
    ..int64s = <Int64>[Int64(-1), Int64(0), _int64Min, _int64Max]
    ..uint64s = <Uint64>[Uint64(0), Uint64(1), _uint64HighBit, _uint64Max]
    ..int64ByName = <String, Int64>{
      'negative': Int64(-1),
      'min': _int64Min,
      'max': _int64Max,
    }
    ..uint64ByName = <String, Uint64>{
      'zero': Uint64(0),
      'highBit': _uint64HighBit,
      'max': _uint64Max,
    };
}

void _expectNumericContainerEqual(
  NumericContainerEnvelope actual,
  NumericContainerEnvelope expected,
) {
  expect(actual.int64s, equals(expected.int64s));
  expect(actual.uint64s, equals(expected.uint64s));
  expect(actual.int64ByName, equals(expected.int64ByName));
  expect(actual.uint64ByName, equals(expected.uint64ByName));
}

void main() {
  group('collection serializer', () {
    test('round-trips empty root containers', () {
      final fory = Fory();

      final list =
          fory.deserialize<Object?>(fory.serialize(<Object?>[])) as List;
      final set = fory.deserialize<Object?>(fory.serialize(<Object?>{})) as Set;
      final map = fory
          .deserialize<Object?>(fory.serialize(<Object?, Object?>{})) as Map;

      expect(list, isA<List>());
      expect(set, isA<Set>());
      expect(map, isA<Map>());
      expect(list, isEmpty);
      expect(set, isEmpty);
      expect(map, isEmpty);
    });

    test('round-trips root list with mixed values and nested containers', () {
      final fory = Fory();
      final value = <Object?>[
        1,
        true,
        'alpha',
        null,
        <Object?>[1, 2, 3],
        <Object?, Object?>{
          'nested': 'map',
          null: <Object?>[null, 'x'],
        },
        <Object?>{'a', null, 'b'},
      ];

      final roundTrip =
          fory.deserialize<Object?>(fory.serialize(value)) as List;

      expect(_describeValue(roundTrip), equals(_describeValue(value)));
      expect(roundTrip[4], isA<List>());
      expect(roundTrip[5], isA<Map>());
      expect(roundTrip[6], isA<Set>());
    });

    test('round-trips root set with mixed values and nested containers', () {
      final fory = Fory();
      final value = <Object?>{
        'alpha',
        null,
        1,
        <Object?>['nested', 1],
        <Object?, Object?>{'k': 'v'},
        <Object?>{'x', 'y'},
      };

      final roundTrip = fory.deserialize<Object?>(fory.serialize(value)) as Set;

      expect(_describeValue(roundTrip), equals(_describeValue(value)));
    });

    test('round-trips root map with null keys values and nested containers',
        () {
      final fory = Fory();
      final value = <Object?, Object?>{
        null: 'null-key',
        'null-value': null,
        'numbers': <Object?>[1, 2, 3],
        'nested-map': <Object?, Object?>{
          'left': 1,
          'right': <Object?>['x', null, 'y'],
        },
        'set': <Object?>{'a', 'b', null},
      };

      final roundTrip = fory.deserialize<Object?>(fory.serialize(value)) as Map;

      expect(_describeValue(roundTrip), equals(_describeValue(value)));
      expect(roundTrip.keys.toList(), orderedEquals(value.keys.toList()));
    });

    test('round-trips nested collection structures', () {
      final fory = Fory();
      final value = <Object?, Object?>{
        'listOfMaps': <Object?>[
          <Object?, Object?>{
            'id': 1,
            'tags': <Object?>{'a', 'b'},
            'children': <Object?>[1, null, 2],
          },
          <Object?, Object?>{
            'id': 2,
            'children': <Object?>[
              <Object?, Object?>{'deep': 'value'},
            ],
          },
        ],
        'setOfLists': <Object?>{
          <Object?>[1, 2],
          <Object?>[3, null, 4],
        },
        'mapOfContainers': <Object?, Object?>{
          'left': <Object?, Object?>{
            'numbers': <Object?>[1, 2, 3],
            'flags': <Object?>{true, false},
          },
          'right': <Object?, Object?>{
            'more': <Object?, Object?>{
              'nested': <Object?>['x', 'y'],
            },
          },
        },
      };

      final roundTrip = fory.deserialize<Object?>(fory.serialize(value)) as Map;

      expect(_describeValue(roundTrip), equals(_describeValue(value)));
      expect(roundTrip['listOfMaps'], isA<List>());
      expect(roundTrip['setOfLists'], isA<Set>());
      expect(roundTrip['mapOfContainers'], isA<Map>());
    });

    test('trackRef false does not preserve repeated nested collections', () {
      final fory = Fory();
      final sharedList = <Object?>['shared'];
      final sharedMap = <Object?, Object?>{'value': 1};

      final listRoundTrip = fory.deserialize<Object?>(
          fory.serialize(<Object?>[sharedList, sharedList])) as List;
      final mapRoundTrip = fory.deserialize<Object?>(
        fory.serialize(<Object?, Object?>{'a': sharedMap, 'b': sharedMap}),
      ) as Map;

      expect(
          _describeValue(listRoundTrip),
          equals(_describeValue(<Object?>[
            sharedList,
            sharedList,
          ])));
      expect(identical(listRoundTrip[0], listRoundTrip[1]), isFalse);
      expect(identical(mapRoundTrip['a'], mapRoundTrip['b']), isFalse);
    });

    test('trackRef true preserves repeated nested collections', () {
      final fory = Fory();
      final sharedList = <Object?>['shared'];
      final sharedMap = <Object?, Object?>{'value': 1};
      final nested = <Object?, Object?>{
        'list': <Object?>[sharedList, sharedList],
        'map': <Object?, Object?>{'a': sharedMap, 'b': sharedMap},
      };

      final roundTrip = fory.deserialize<Object?>(
        fory.serialize(nested, trackRef: true),
      ) as Map;
      final listRoundTrip = roundTrip['list'] as List;
      final mapRoundTrip = roundTrip['map'] as Map;

      expect(_describeValue(roundTrip), equals(_describeValue(nested)));
      expect(identical(listRoundTrip[0], listRoundTrip[1]), isTrue);
      expect(identical(mapRoundTrip['a'], mapRoundTrip['b']), isTrue);
    });

    test('trackRef true preserves self-referential root lists', () {
      final fory = Fory();
      final value = <Object?>[];
      value.add(value);

      final roundTrip = fory.deserialize<Object?>(
        fory.serialize(value, trackRef: true),
      ) as List;

      expect(roundTrip, hasLength(1));
      expect(identical(roundTrip, roundTrip[0]), isTrue);
    });

    test('trackRef true preserves self-referential root maps', () {
      final fory = Fory();
      final value = <Object?, Object?>{};
      value['self'] = value;

      final roundTrip = fory.deserialize<Object?>(
        fory.serialize(value, trackRef: true),
      ) as Map;

      expect(roundTrip.keys, contains('self'));
      expect(identical(roundTrip, roundTrip['self']), isTrue);
    });

    test('round-trips large list, set, and map payloads', () {
      final fory = Fory();
      final largeList = List<Object?>.generate(600, (index) => 'item-$index',
          growable: false);
      final largeSet = Set<Object?>.of(largeList);
      final largeMap = <Object?, Object?>{
        for (var index = 0; index < 320; index += 1) 'k$index': index,
      };

      final listRoundTrip =
          fory.deserialize<Object?>(fory.serialize(largeList)) as List;
      final setRoundTrip =
          fory.deserialize<Object?>(fory.serialize(largeSet)) as Set;
      final mapRoundTrip =
          fory.deserialize<Object?>(fory.serialize(largeMap)) as Map;

      expect(_describeValue(listRoundTrip), equals(_describeValue(largeList)));
      expect(_describeValue(setRoundTrip), equals(_describeValue(largeSet)));
      expect(_describeValue(mapRoundTrip), equals(_describeValue(largeMap)));
      expect(mapRoundTrip.keys.toList(), orderedEquals(largeMap.keys.toList()));
    });

    test(
        'reuses the same fory instance and target buffer across collection shapes',
        () {
      final fory = Fory();
      final buffer = Buffer();
      final cases = <Object?>[
        <Object?>[1, 2, 3],
        <Object?>{'a', null, 'b'},
        <Object?, Object?>{
          'nested': <Object?>[
            <Object?, Object?>{'x': 1},
            null,
            <Object?>{'y', 'z'},
          ],
        },
        <Object?, Object?>{
          'shared': <Object?>[
            <Object?>['x'],
            <Object?>['x'],
          ],
        },
      ];

      for (final value in cases) {
        fory.serializeTo(value, buffer);
        final roundTrip = fory.deserializeFrom<Object?>(buffer);
        expect(_describeValue(roundTrip), equals(_describeValue(value)));
        expect(buffer.readableBytes, equals(0));
      }
    });

    test('round-trips generated Int64 and Uint64 list/map fields', () {
      for (final compatible in <bool>[false, true]) {
        final fory = Fory(compatible: compatible);
        _registerNumericContainerEnvelope(fory);

        final value = _numericContainerEnvelope();
        final roundTrip = fory.deserialize<NumericContainerEnvelope>(
          fory.serialize(value),
        );
        _expectNumericContainerEqual(roundTrip, value);
      }
    });

    test('enforces maxCollectionSize for list set and map', () {
      final fory = Fory(maxCollectionSize: 2);

      expect(
        () => fory.serialize(<Object?>[1, 2, 3]),
        throwsA(isA<StateError>()),
      );
      expect(
        () => fory.serialize(<Object?>{1, 2, 3}),
        throwsA(isA<StateError>()),
      );
      expect(
        () => fory.serialize(<Object?, Object?>{'a': 1, 'b': 2, 'c': 3}),
        throwsA(isA<StateError>()),
      );
    });
  });
}

String _describeValue(Object? value) {
  if (value == null) {
    return 'null';
  }
  if (value is String) {
    return jsonEncode(value);
  }
  if (value is bool || value is num) {
    return value.toString();
  }
  if (value is List) {
    return '[${value.map(_describeValue).join(',')}]';
  }
  if (value is Set) {
    final items = value.map(_describeValue).toList()..sort();
    return 'set(${items.join(',')})';
  }
  if (value is Map) {
    final entries = value.entries
        .map((entry) =>
            '${_describeValue(entry.key)}:${_describeValue(entry.value)}')
        .join(',');
    return 'map($entries)';
  }
  return jsonEncode(value.toString());
}
