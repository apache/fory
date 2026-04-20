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

part 'container_ref_test.fory.dart';

@ForyStruct()
class Node {
  Node();

  String name = '';
}

/// Struct with @ListType that enables ref tracking on list elements.
@ForyStruct()
class RefListContainer {
  RefListContainer();

  @ListType(element: ValueType.ref())
  List<Node> items = <Node>[];
}

/// Struct with no @ListType annotation — list elements are NOT ref-tracked.
@ForyStruct()
class NoRefListContainer {
  NoRefListContainer();

  List<Node> items = <Node>[];
}

/// Struct with @MapType that enables ref tracking on map values.
@ForyStruct()
class RefMapValueContainer {
  RefMapValueContainer();

  @MapType(value: ValueType.ref())
  Map<String, Node> entries = <String, Node>{};
}

/// Struct with no @MapType — map values are NOT ref-tracked.
@ForyStruct()
class NoRefMapContainer {
  NoRefMapContainer();

  Map<String, Node> entries = <String, Node>{};
}

/// Struct with @MapType that enables ref tracking on map keys.
@ForyStruct()
class RefMapKeyContainer {
  RefMapKeyContainer();

  @MapType(key: ValueType.ref())
  Map<Node, String> entries = <Node, String>{};
}

/// Struct with nested list of maps where map values are ref-tracked.
@ForyStruct()
class NestedListOfMapContainer {
  NestedListOfMapContainer();

  @ListType(
    element: MapType(value: ValueType.ref()),
  )
  List<Map<String, Node>> groups = <Map<String, Node>>[];
}

/// Struct with a map whose values are ref-tracked lists.
@ForyStruct()
class NestedMapOfListContainer {
  NestedMapOfListContainer();

  @MapType(
    value: ListType(element: ValueType.ref()),
  )
  Map<String, List<Node>> groups = <String, List<Node>>{};
}

void _registerAll(Fory fory) {
  ContainerRefTestFory.register(fory, Node,
      namespace: 'test', typeName: 'Node');
  ContainerRefTestFory.register(fory, RefListContainer,
      namespace: 'test', typeName: 'RefListContainer');
  ContainerRefTestFory.register(fory, NoRefListContainer,
      namespace: 'test', typeName: 'NoRefListContainer');
  ContainerRefTestFory.register(fory, RefMapValueContainer,
      namespace: 'test', typeName: 'RefMapValueContainer');
  ContainerRefTestFory.register(fory, NoRefMapContainer,
      namespace: 'test', typeName: 'NoRefMapContainer');
  ContainerRefTestFory.register(fory, RefMapKeyContainer,
      namespace: 'test', typeName: 'RefMapKeyContainer');
  ContainerRefTestFory.register(fory, NestedListOfMapContainer,
      namespace: 'test', typeName: 'NestedListOfMapContainer');
  ContainerRefTestFory.register(fory, NestedMapOfListContainer,
      namespace: 'test', typeName: 'NestedMapOfListContainer');
}

void main() {
  late Fory fory;

  setUp(() {
    fory = Fory();
    _registerAll(fory);
  });

  group('list element ref via @ListType annotation', () {
    test('shared list elements preserve identity with element ref enabled', () {
      final shared = Node()..name = 'shared';
      final container = RefListContainer()
        ..items = <Node>[shared, shared, shared];
      final bytes = fory.serialize(container);
      final result = fory.deserialize<RefListContainer>(bytes);

      expect(result.items, hasLength(3));
      expect(result.items[0].name, equals('shared'));
      expect(identical(result.items[0], result.items[1]), isTrue);
      expect(identical(result.items[1], result.items[2]), isTrue);
    });

    test('shared list elements are different instances without annotation', () {
      final shared = Node()..name = 'shared';
      final container = NoRefListContainer()
        ..items = <Node>[shared, shared, shared];
      final bytes = fory.serialize(container);
      final result = fory.deserialize<NoRefListContainer>(bytes);

      expect(result.items, hasLength(3));
      expect(result.items[0].name, equals('shared'));
      expect(result.items[1].name, equals('shared'));
      expect(identical(result.items[0], result.items[1]), isFalse);
      expect(identical(result.items[1], result.items[2]), isFalse);
    });
  });

  group('map value ref via @MapType annotation', () {
    test('shared map values preserve identity with value ref enabled', () {
      final shared = Node()..name = 'val';
      final container = RefMapValueContainer()
        ..entries = <String, Node>{'a': shared, 'b': shared};
      final bytes = fory.serialize(container);
      final result = fory.deserialize<RefMapValueContainer>(bytes);

      expect(result.entries, hasLength(2));
      expect(result.entries['a']!.name, equals('val'));
      expect(identical(result.entries['a'], result.entries['b']), isTrue);
    });

    test('shared map values are different instances without annotation', () {
      final shared = Node()..name = 'val';
      final container = NoRefMapContainer()
        ..entries = <String, Node>{'a': shared, 'b': shared};
      final bytes = fory.serialize(container);
      final result = fory.deserialize<NoRefMapContainer>(bytes);

      expect(result.entries, hasLength(2));
      expect(result.entries['a']!.name, equals('val'));
      expect(result.entries['b']!.name, equals('val'));
      expect(identical(result.entries['a'], result.entries['b']), isFalse);
    });
  });

  group('map key ref via @MapType annotation', () {
    test('shared map keys preserve identity with key ref enabled', () {
      final shared = Node()..name = 'key';
      final container = RefMapKeyContainer()
        ..entries = <Node, String>{shared: 'x'};
      final bytes = fory.serialize(container);
      final result = fory.deserialize<RefMapKeyContainer>(bytes);

      expect(result.entries, hasLength(1));
      final key = result.entries.keys.first;
      expect(key.name, equals('key'));
    });
  });

  group('nested container ref via @ListType/@MapType annotation', () {
    test('list of maps with ref-tracked values preserves identity across maps',
        () {
      final shared = Node()..name = 'deep';
      final container = NestedListOfMapContainer()
        ..groups = <Map<String, Node>>[
          <String, Node>{'x': shared},
          <String, Node>{'y': shared},
        ];
      final bytes = fory.serialize(container);
      final result = fory.deserialize<NestedListOfMapContainer>(bytes);

      expect(result.groups, hasLength(2));
      expect(result.groups[0]['x']!.name, equals('deep'));
      expect(identical(result.groups[0]['x'], result.groups[1]['y']), isTrue);
    });

    test('map of lists with ref-tracked elements preserves identity across lists',
        () {
      final shared = Node()..name = 'maplist';
      final container = NestedMapOfListContainer()
        ..groups = <String, List<Node>>{
          'a': <Node>[shared, shared],
          'b': <Node>[shared],
        };
      final bytes = fory.serialize(container);
      final result = fory.deserialize<NestedMapOfListContainer>(bytes);

      expect(result.groups['a'], hasLength(2));
      expect(result.groups['b'], hasLength(1));
      expect(result.groups['a']![0].name, equals('maplist'));
      expect(identical(result.groups['a']![0], result.groups['a']![1]), isTrue);
      expect(identical(result.groups['a']![0], result.groups['b']![0]), isTrue);
    });
  });
}
