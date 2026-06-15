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

part 'nested_type_spec_test.fory.dart';

@ForyStruct()
class NestedFixedContainer {
  NestedFixedContainer();

  @MapField(value: ListType(element: Int32Type(encoding: Encoding.fixed)))
  Map<String, List<int?>> nested = <String, List<int?>>{};
}

@ForyStruct()
class NestedVarintContainer {
  NestedVarintContainer();

  @MapField(value: ListType(element: Int32Type(encoding: Encoding.varint)))
  Map<String, List<int?>> nested = <String, List<int?>>{};
}

@ForyStruct()
class NestedRefNode {
  NestedRefNode();

  String name = '';
}

@ForyStruct()
class NestedRefWriterContainer {
  NestedRefWriterContainer();

  @ListField(element: DeclaredType(ref: true))
  List<NestedRefNode> items = <NestedRefNode>[];
}

@ForyStruct()
class NestedRefReaderContainer {
  NestedRefReaderContainer();

  List<NestedRefNode> items = <NestedRefNode>[];
}

@ForyStruct()
class NestedNullableWriterContainer {
  NestedNullableWriterContainer();

  @ListField(element: StringType(nullable: true))
  List<String?> items = <String?>[];
}

@ForyStruct()
class NestedNullableReaderContainer {
  NestedNullableReaderContainer();

  List<String> items = <String>[];
}

void _registerFixedCompatible(Fory fory) {
  NestedTypeSpecTestForyModule.register(
    fory,
    NestedFixedContainer,
    name: 'nested.NestedIntContainer',
  );
}

void _registerVarintConsistent(Fory fory) {
  NestedTypeSpecTestForyModule.register(
    fory,
    NestedVarintContainer,
    name: 'nested.NestedIntContainer',
  );
}

void _registerRefWriter(Fory fory) {
  NestedTypeSpecTestForyModule.register(
    fory,
    NestedRefNode,
    name: 'nested.NestedRefNode',
  );
  NestedTypeSpecTestForyModule.register(
    fory,
    NestedRefWriterContainer,
    name: 'nested.NestedRefContainer',
  );
}

void _registerRefReader(Fory fory) {
  NestedTypeSpecTestForyModule.register(
    fory,
    NestedRefNode,
    name: 'nested.NestedRefNode',
  );
  NestedTypeSpecTestForyModule.register(
    fory,
    NestedRefReaderContainer,
    name: 'nested.NestedRefContainer',
  );
}

void _registerNullableWriter(Fory fory) {
  NestedTypeSpecTestForyModule.register(
    fory,
    NestedNullableWriterContainer,
    name: 'nested.NestedNullableContainer',
  );
}

void _registerNullableReader(Fory fory) {
  NestedTypeSpecTestForyModule.register(
    fory,
    NestedNullableReaderContainer,
    name: 'nested.NestedNullableContainer',
  );
}

void main() {
  group('nested type specs', () {
    test('compatible mode rejects nested scalar encoding drift', () {
      final writer = Fory(compatible: true);
      final reader = Fory(compatible: true);
      _registerFixedCompatible(writer);
      _registerVarintConsistent(reader);

      final bytes = writer.serialize(
        NestedFixedContainer()
          ..nested = <String, List<int?>>{
            'a': <int?>[1, null, -7],
          },
      );

      expect(
        () => reader.deserialize<NestedVarintContainer>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('incompatible local and remote schemas'),
          ),
        ),
      );
    });

    test('schema-consistent mode rejects nested encoding hash mismatches', () {
      final writer = Fory(compatible: false);
      final reader = Fory(compatible: false);
      _registerFixedCompatible(writer);
      _registerVarintConsistent(reader);

      final bytes = writer.serialize(
        NestedFixedContainer()
          ..nested = <String, List<int?>>{
            'a': <int?>[1, null, -7],
          },
      );

      expect(
        () => reader.deserialize<NestedVarintContainer>(bytes),
        throwsA(
          isA<StateError>().having(
            (error) => error.toString(),
            'message',
            contains('Struct schema version mismatch'),
          ),
        ),
      );
    });

    test('nested ref metadata stays out of schema hash and preserves refs', () {
      final writer = Fory(compatible: true);
      final reader = Fory(compatible: true);
      _registerRefWriter(writer);
      _registerRefReader(reader);

      final shared = NestedRefNode()..name = 'shared';
      final result = reader.deserialize<NestedRefReaderContainer>(
        writer.serialize(
          NestedRefWriterContainer()..items = <NestedRefNode>[shared, shared],
          trackRef: true,
        ),
      );

      expect(result.items, hasLength(2));
      expect(result.items[0].name, equals('shared'));
      expect(identical(result.items[0], result.items[1]), isTrue);
    });

    test(
      'schema-consistent mode ignores nested ref-only schema differences',
      () {
        final writer = Fory(compatible: false);
        final reader = Fory(compatible: false);
        _registerRefWriter(writer);
        _registerRefReader(reader);

        final shared = NestedRefNode()..name = 'shared';
        final result = reader.deserialize<NestedRefReaderContainer>(
          writer.serialize(
            NestedRefWriterContainer()..items = <NestedRefNode>[shared, shared],
            trackRef: true,
          ),
        );

        expect(result.items, hasLength(2));
        expect(identical(result.items[0], result.items[1]), isTrue);
      },
    );

    test(
      'schema-consistent mode ignores nested nullable-only schema differences',
      () {
        final writer = Fory(compatible: false);
        final reader = Fory(compatible: false);
        _registerNullableWriter(writer);
        _registerNullableReader(reader);

        final result = reader.deserialize<NestedNullableReaderContainer>(
          writer.serialize(
            NestedNullableWriterContainer()..items = <String?>['a', 'b'],
          ),
        );

        expect(result.items, orderedEquals(<String>['a', 'b']));
      },
    );
  });
}
