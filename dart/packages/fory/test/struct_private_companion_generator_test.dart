// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

@TestOn('vm')
library;

import 'package:test/test.dart';

import 'codegen_test_support.dart';

const String _annotationImports = '''
import 'package:fory/src/annotation/fory_field.dart';
import 'package:fory/src/annotation/fory_struct.dart';
''';

String _librarySource(String inputPath, String body, {String directives = ''}) {
  return foryTestLibrarySource(
    inputPath,
    body,
    directives: directives,
    imports: _annotationImports,
  );
}

Future<String> _generate({
  required String inputPath,
  required String source,
  Map<String, String> additionalAssets = const <String, String>{},
}) async {
  return generateForySource(
    inputPath: inputPath,
    source: source,
    additionalAssets: additionalAssets,
  );
}

Future<void> _expectGenerationError({
  required String inputPath,
  required String source,
  required String message,
  Map<String, String> additionalAssets = const <String, String>{},
}) async {
  await expectForyGenerationError(
    inputPath: inputPath,
    source: source,
    message: message,
    additionalAssets: additionalAssets,
  );
}

String _providerSource({
  String inputPath = 'test/private_provider.dart',
  String boundaryName = 'Boundary',
}) {
  return _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class $boundaryName {
  int _secret = 0;
}
''');
}

void _expectOnlyVisibilityBridge(String output, Iterable<String> owners) {
  for (final owner in owners) {
    expect(
      output,
      contains(
        r'abstract final class $'
        '${owner}ForyFieldAccess',
      ),
    );
    expect(output, isNot(contains('_${owner}ForySerializer')));
  }
  expect(output, isNot(contains('GeneratedStructSchema<')));
  expect(output, isNot(contains('GeneratedStructSerializer<')));
  expect(output, isNot(contains('ReadContext')));
  expect(output, isNot(contains('WriteContext')));
  expect(output, isNot(contains('Serializer<')));
  expect(output, isNot(contains('ForyModule')));
  expect(output, isNot(contains('Object?')));
  expect(output, isNot(contains('dynamic')));
  expect(output, isNot(contains('Function')));
  expect(output, isNot(contains('callback')));
  expect(output, isNot(contains('reflection')));
  expect(output, isNot(contains('register')));
}

void main() {
  group('provider ownership', () {
    test('supports abstract, open generic, and mixin providers', () async {
      const inputPath = 'test/provider_shapes.dart';
      final output = await _generate(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class AbstractBoundary {
  int _abstractValue = 0;
}

@ForyStruct(exposePrivateFields: true)
class GenericBoundary<T extends num> {
  T? _genericValue;
}

@ForyStruct(exposePrivateFields: true)
mixin SecretMixin {
  int _mixedValue = 0;
}
'''),
      );

      _expectOnlyVisibilityBridge(output, const <String>[
        'AbstractBoundary',
        'GenericBoundary',
        'SecretMixin',
      ]);
      expect(
        output,
        matches(
          RegExp(
            r'static T\? \$g[0-9a-f]{16}<T extends num>\('
            r'GenericBoundary<T> value\)',
          ),
        ),
      );
      expect(output, contains('GenericBoundary<T> value'));
      expect(output, contains('SecretMixin value'));
    });

    test('concrete provider owns both serializer and companion', () async {
      const inputPath = 'test/concrete_provider.dart';
      final output = await _generate(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
class ConcreteProvider {
  int _secret = 0;
  int visible = 1;
}
'''),
      );

      expect(
        output,
        contains(r'abstract final class $ConcreteProviderForyFieldAccess'),
      );
      expect(output, contains('final class _ConcreteProviderForySerializer'));
      expect(output, contains('GeneratedStructSchema<ConcreteProvider>'));
      expect(output, contains('ConcreteProviderForyModule'));
    });

    test('concrete exposure leaves its serializer surface unchanged', () async {
      const inputPath = 'test/concrete_surface.dart';
      final normal = await _generate(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct()
class ConcreteSurface {
  int _value = 0;
}
'''),
      );
      final exposed = await _generate(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
class ConcreteSurface {
  int _value = 0;
}
'''),
      );
      const marker =
          'const List<GeneratedFieldInfo> _concreteSurfaceForyFieldInfo';

      expect(
        exposed.substring(exposed.indexOf(marker)),
        normal.substring(normal.indexOf(marker)),
      );
      expect(exposed, contains(r'class $ConcreteSurfaceForyFieldAccess'));
      expect(normal, isNot(contains(r'$ConcreteSurfaceForyFieldAccess')));
    });

    test(
      'keeps concrete omission and companion ownership independent',
      () async {
        const inputPath = 'test/concrete_omission_provider.dart';
        final output = await _generate(
          inputPath: inputPath,
          source: _librarySource(inputPath, '''
class ConcreteBase {
  int _baseSecret = 0;
}

@ForyStruct(
  exposePrivateFields: true,
  ignoreInheritedPrivateFields: true,
)
class ConcreteBoundary extends ConcreteBase {
  int _ownSecret = 0;
}
'''),
        );

        expect(output, contains("identifier: '_own_secret',"));
        expect(output, isNot(contains("identifier: '_base_secret',")));
        expect(output, contains('=> value._baseSecret;'));
        expect(output, contains('value._baseSecret = fieldValue;'));
        expect(output, contains('=> value._ownSecret;'));
        expect(output, contains('value._ownSecret = fieldValue;'));
      },
    );

    test('exposePrivateFields defaults to false', () async {
      const inputPath = 'test/default_false.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct()
abstract class Boundary {
  int _secret = 0;
}
'''),
        message: 'does not own a concrete generated serializer',
      );
    });

    test('unexposed parent remains strict by default', () async {
      const cases = <({String name, String annotation})>[
        (name: 'default', annotation: '@ForyStruct()'),
        (
          name: 'consumer_exposure',
          annotation: '@ForyStruct(exposePrivateFields: true)',
        ),
      ];
      for (final testCase in cases) {
        final inputPath = 'test/${testCase.name}_unexposed_parent.dart';
        await _expectGenerationError(
          inputPath: inputPath,
          source: _librarySource(inputPath, '''
${testCase.annotation}
class Child extends Boundary {
  int _childSecret = 0;
}
''', directives: "import 'unexposed_parent.dart';"),
          additionalAssets: const <String, String>{
            'fory|test/unexposed_parent.dart': '''
abstract class Boundary {
  int _parentSecret = 0;
}
''',
          },
          message:
              'No public hierarchy boundary in the declaring library is '
              'annotated with @ForyStruct(exposePrivateFields: true)',
        );
      }
    });

    test('external target cannot expose private fields', () async {
      const inputPath = 'test/external_exposure.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
class Target {
  int value = 0;
}

@ForyStruct(target: Target, exposePrivateFields: true)
abstract final class TargetSchema {
  late final int value;
}
'''),
        message:
            'ForyStruct.exposePrivateFields cannot be used with '
            'ForyStruct.target',
      );
    });

    test('external target cannot ignore inherited private fields', () async {
      const inputPath = 'test/external_inherited_private_omission.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
class Target {
  int value = 0;
}

@ForyStruct(target: Target, ignoreInheritedPrivateFields: true)
abstract final class TargetSchema {
  late final int value;
}
'''),
        message:
            'ForyStruct.ignoreInheritedPrivateFields cannot be used with '
            'ForyStruct.target',
      );
    });

    test('empty or fully ignored provider is rejected', () async {
      const inputPath = 'test/empty_provider.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  int visible = 0;

  @ForyField(ignore: true)
  int _ignored = 0;
}
'''),
        message: 'has no non-ignored private storage',
      );
    });

    test('provider boundary must be public and externally usable', () async {
      const cases = <({String name, String declaration, String message})>[
        (
          name: 'private',
          declaration: '''
@ForyStruct(exposePrivateFields: true)
abstract class _Boundary {
  int _secret = 0;
}
''',
          message: 'requires a public hierarchy boundary',
        ),
        (
          name: 'final',
          declaration: '''
@ForyStruct(exposePrivateFields: true)
final class Boundary {
  int _secret = 0;
}
''',
          message: 'cannot be extended or mixed in outside',
        ),
      ];

      for (final testCase in cases) {
        final inputPath = 'test/${testCase.name}_provider.dart';
        await _expectGenerationError(
          inputPath: inputPath,
          source: _librarySource(inputPath, testCase.declaration),
          message: testCase.message,
        );
      }
    });

    test('provider-only boundary rejects schema options', () async {
      const cases = <({String name, String annotation, String message})>[
        (
          name: 'evolving',
          annotation: '@ForyStruct(exposePrivateFields: true, evolving: false)',
          message: 'ForyStruct.evolving has no meaning',
        ),
        (
          name: 'constructor',
          annotation:
              "@ForyStruct(exposePrivateFields: true, constructor: 'named')",
          message: 'ForyStruct.constructor has no meaning',
        ),
      ];

      for (final testCase in cases) {
        final inputPath = 'test/${testCase.name}_provider_option.dart';
        await _expectGenerationError(
          inputPath: inputPath,
          source: _librarySource(inputPath, '''
${testCase.annotation}
abstract class Boundary {
  int _secret = 0;
}
'''),
          message: testCase.message,
        );
      }
    });

    test('provider-only declarations cannot own omission policy', () async {
      const cases = <({String name, String declaration})>[
        (
          name: 'abstract',
          declaration: '''
@ForyStruct(
  exposePrivateFields: true,
  ignoreInheritedPrivateFields: true,
)
abstract class Boundary {
  int _secret = 0;
}
''',
        ),
        (
          name: 'generic',
          declaration: '''
@ForyStruct(
  exposePrivateFields: true,
  ignoreInheritedPrivateFields: true,
)
class Boundary<T> {
  int _secret = 0;
}
''',
        ),
        (
          name: 'mixin',
          declaration: '''
@ForyStruct(
  exposePrivateFields: true,
  ignoreInheritedPrivateFields: true,
)
mixin Boundary {
  int _secret = 0;
}
''',
        ),
      ];

      for (final testCase in cases) {
        final inputPath = 'test/${testCase.name}_omission_provider.dart';
        await _expectGenerationError(
          inputPath: inputPath,
          source: _librarySource(inputPath, testCase.declaration),
          message:
              'ForyStruct.ignoreInheritedPrivateFields is valid only on an '
              'ordinary concrete declaration',
        );
      }
    });
  });

  group('companion API', () {
    test('emits mutable accessors and getter-only final access', () async {
      const inputPath = 'test/accessor_shape.dart';
      final output = await _generate(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  int _mutable = 0;
  final int _immutable = 1;
}
'''),
      );

      _expectOnlyVisibilityBridge(output, const <String>['Boundary']);
      expect(
        RegExp(
          r'static int \$g[0-9a-f]{16}\(Boundary value\)',
        ).allMatches(output),
        hasLength(2),
      );
      expect(
        RegExp(r'static void \$s[0-9a-f]{16}\(').allMatches(output),
        hasLength(1),
      );
      expect(output, contains('=> value._mutable;'));
      expect(output, contains('value._mutable = fieldValue;'));
      expect(output, contains('=> value._immutable;'));
      expect(output, isNot(contains('value._immutable = fieldValue;')));
    });

    test('public boundary exposes a private ancestor slot', () async {
      const providerPath = 'test/private_ancestor_provider.dart';
      final providerSource = _librarySource(providerPath, '''
class _PrivateStorage {
  int _secret = 0;
}

@ForyStruct(exposePrivateFields: true)
abstract class Boundary extends _PrivateStorage {}
''');
      final providerOutput = await _generate(
        inputPath: providerPath,
        source: providerSource,
      );

      expect(
        providerOutput,
        contains(r'abstract final class $BoundaryForyFieldAccess'),
      );
      expect(providerOutput, contains('Boundary value'));
      expect(providerOutput, contains('=> value._secret;'));
      expect(providerOutput, contains('value._secret = fieldValue;'));
      expect(
        providerOutput,
        isNot(contains(r'$_PrivateStorageForyFieldAccess')),
      );
      final getterName =
          RegExp(
            r'static int (\$g[0-9a-f]{16})\(Boundary value\)',
          ).firstMatch(providerOutput)!.group(1)!;
      final setterName =
          RegExp(
            r'static void (\$s[0-9a-f]{16})\(',
          ).firstMatch(providerOutput)!.group(1)!;

      const childPath = 'test/private_ancestor_child.dart';
      final childOutput = await _generate(
        inputPath: childPath,
        source: _librarySource(childPath, '''
@ForyStruct()
class Child extends Boundary {
  int local = 0;
}
''', directives: "import 'private_ancestor_provider.dart';"),
        additionalAssets: <String, String>{
          'fory|$providerPath': providerSource,
        },
      );

      expect(childOutput, contains(r'$BoundaryForyFieldAccess.' + getterName));
      expect(childOutput, contains(r'$BoundaryForyFieldAccess.' + setterName));
      expect(childOutput, isNot(contains('value._secret')));
      expect(childOutput, isNot(contains('_BoundaryForySerializer')));
    });

    test('uses one companion for each declaring library', () async {
      const aPath = 'test/library_a.dart';
      const bPath = 'test/library_b.dart';
      const childPath = 'test/multi_library_child.dart';
      final aSource = _librarySource(aPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class ABoundary {
  int _a = 0;
}
''');
      final bSource = _librarySource(
        bPath,
        '''
@ForyStruct(exposePrivateFields: true)
abstract class BBoundary extends ABoundary {
  int _b = 0;
}
''',
        directives: '''
import 'library_a.dart';
export 'library_a.dart';
''',
      );
      final output = await _generate(
        inputPath: childPath,
        source: _librarySource(childPath, '''
@ForyStruct()
class Child extends BBoundary {}
''', directives: "import 'library_b.dart';"),
        additionalAssets: <String, String>{
          'fory|$aPath': aSource,
          'fory|$bPath': bSource,
        },
      );

      expect(output, contains(r'$ABoundaryForyFieldAccess.$g'));
      expect(output, contains(r'$ABoundaryForyFieldAccess.$s'));
      expect(output, contains(r'$BBoundaryForyFieldAccess.$g'));
      expect(output, contains(r'$BBoundaryForyFieldAccess.$s'));
      expect(output, isNot(contains('_ABoundaryForySerializer')));
      expect(output, isNot(contains('_BBoundaryForySerializer')));
    });

    test('uses stable distinct getter and setter digests', () async {
      const inputPath = 'test/digest_provider.dart';
      final source = _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  int _first = 0;
  int _second = 0;
}
''');
      final first = await _generate(inputPath: inputPath, source: source);
      final second = await _generate(inputPath: inputPath, source: source);

      expect(second, first);
      final getterDigests =
          RegExp(
            r'static int \$g([0-9a-f]{16})\(',
          ).allMatches(first).map((match) => match.group(1)!).toSet();
      final setterDigests =
          RegExp(
            r'static void \$s([0-9a-f]{16})\(',
          ).allMatches(first).map((match) => match.group(1)!).toSet();
      expect(getterDigests, hasLength(2));
      expect(setterDigests, getterDigests);
    });
  });

  group('consumer resolution', () {
    final providerSource = _providerSource();

    test('omits unexposed inherited private storage', () async {
      const inputPath = 'test/omitting_unexposed_consumer.dart';
      final output = await _generate(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(ignoreInheritedPrivateFields: true)
class Child extends UnexposedParent {
  int _childSecret = 0;
}
''', directives: "import 'unexposed_parent.dart';"),
        additionalAssets: const <String, String>{
          'fory|test/unexposed_parent.dart': '''
class UnexposedParent {
  int _secret = 0;
  int inheritedPublic = 0;
}
''',
        },
      );

      expect(output, contains("identifier: '_child_secret',"));
      expect(output, contains("identifier: 'inherited_public',"));
      expect(output, isNot(contains("identifier: '_secret',")));
      expect(output, isNot(contains('ForyFieldAccess')));
    });

    test('omits private storage even when a companion exists', () async {
      const inputPath = 'test/omitting_exposed_consumer.dart';
      final output = await _generate(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(ignoreInheritedPrivateFields: true)
class Child extends Boundary {
  int value = 0;
}
''', directives: "import 'private_provider.dart';"),
        additionalAssets: <String, String>{
          'fory|test/private_provider.dart': providerSource,
        },
      );

      expect(output, contains("identifier: 'value',"));
      expect(output, isNot(contains("identifier: '_secret',")));
      expect(output, isNot(contains(r'$BoundaryForyFieldAccess')));
    });

    test('resolves direct, prefixed, re-exported, and shown helpers', () async {
      final cases = <
        ({
          String name,
          String directives,
          Map<String, String> assets,
          String parent,
          String expected,
        })
      >[
        (
          name: 'direct',
          directives: "import 'private_provider.dart';",
          assets: const <String, String>{},
          parent: 'Boundary',
          expected: r'$BoundaryForyFieldAccess.$g',
        ),
        (
          name: 'prefixed',
          directives: "import 'private_provider.dart' as p;",
          assets: const <String, String>{},
          parent: 'p.Boundary',
          expected: r'p.$BoundaryForyFieldAccess.$g',
        ),
        (
          name: 'reexported',
          directives: "import 'private_barrel.dart';",
          assets: const <String, String>{
            'fory|test/private_barrel.dart': "export 'private_provider.dart';",
          },
          parent: 'Boundary',
          expected: r'$BoundaryForyFieldAccess.$g',
        ),
        (
          name: 'shown',
          directives:
              r"import 'private_provider.dart' "
              r'show Boundary, $BoundaryForyFieldAccess;',
          assets: const <String, String>{},
          parent: 'Boundary',
          expected: r'$BoundaryForyFieldAccess.$g',
        ),
        (
          name: 'split_unprefixed',
          directives:
              "import 'private_provider.dart' show Boundary;\n"
              r"import 'private_provider.dart' "
              r'show $BoundaryForyFieldAccess;',
          assets: const <String, String>{},
          parent: 'Boundary',
          expected: r'$BoundaryForyFieldAccess.$g',
        ),
        (
          name: 'split_prefixed',
          directives:
              "import 'private_provider.dart' as p show Boundary;\n"
              r"import 'private_provider.dart' as p "
              r'show $BoundaryForyFieldAccess;',
          assets: const <String, String>{},
          parent: 'p.Boundary',
          expected: r'p.$BoundaryForyFieldAccess.$g',
        ),
      ];

      for (final testCase in cases) {
        final inputPath = 'test/${testCase.name}_consumer.dart';
        final output = await _generate(
          inputPath: inputPath,
          source: _librarySource(inputPath, '''
@ForyStruct()
class Child extends ${testCase.parent} {}
''', directives: testCase.directives),
          additionalAssets: <String, String>{
            'fory|test/private_provider.dart': providerSource,
            ...testCase.assets,
          },
        );

        expect(output, contains(testCase.expected));
        expect(
          output,
          contains(testCase.expected.replaceFirst(r'.$g', r'.$s')),
        );
        expect(output, isNot(contains('_BoundaryForySerializer')));
      }
    });

    test('rejects show, hide, and re-export paths missing helper', () async {
      final cases =
          <({String name, String directives, Map<String, String> assets})>[
            (
              name: 'show_missing',
              directives: "import 'private_provider.dart' show Boundary;",
              assets: const <String, String>{},
            ),
            (
              name: 'hide_helper',
              directives:
                  r"import 'private_provider.dart' "
                  r'hide $BoundaryForyFieldAccess;',
              assets: const <String, String>{},
            ),
            (
              name: 'reexport_missing',
              directives: "import 'private_hidden_barrel.dart';",
              assets: const <String, String>{
                'fory|test/private_hidden_barrel.dart':
                    "export 'private_provider.dart' show Boundary;",
              },
            ),
          ];

      for (final testCase in cases) {
        final inputPath = 'test/${testCase.name}_consumer.dart';
        await _expectGenerationError(
          inputPath: inputPath,
          source: _librarySource(inputPath, '''
@ForyStruct()
class Child extends Boundary {}
''', directives: testCase.directives),
          additionalAssets: <String, String>{
            'fory|test/private_provider.dart': providerSource,
            ...testCase.assets,
          },
          message:
              'generated companion is not visible through the child library '
              'imports and re-exports',
        );
      }
    });

    test('rejects an ambiguous companion import namespace', () async {
      const inputPath = 'test/ambiguous_consumer.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct()
class Child extends Boundary {}
''', directives: "import 'ambiguous_barrel.dart';"),
        additionalAssets: <String, String>{
          'fory|test/private_provider.dart': providerSource,
          'fory|test/conflicting_helper.dart': r'''
abstract final class $BoundaryForyFieldAccess {}
''',
          'fory|test/ambiguous_barrel.dart': '''
export 'private_provider.dart';
export 'conflicting_helper.dart';
''',
        },
        message: 'is ambiguous in import namespace',
      );
    });

    test('uses a valid prefix when another prefix is ambiguous', () async {
      const inputPath = 'test/valid_and_ambiguous_consumer.dart';
      final output = await _generate(
        inputPath: inputPath,
        source: _librarySource(
          inputPath,
          '''
@ForyStruct()
class Child extends p.Boundary {}
''',
          directives: '''
import 'private_provider.dart' as p;
import 'ambiguous_barrel.dart' as q;
''',
        ),
        additionalAssets: <String, String>{
          'fory|test/private_provider.dart': providerSource,
          'fory|test/conflicting_helper.dart': r'''
abstract final class $BoundaryForyFieldAccess {}
''',
          'fory|test/ambiguous_barrel.dart': '''
export 'private_provider.dart';
export 'conflicting_helper.dart';
''',
        },
      );

      expect(output, contains(r'p.$BoundaryForyFieldAccess.$g'));
      expect(output, isNot(contains(r'q.$BoundaryForyFieldAccess.$g')));
    });

    test('rejects a local value shadowing the companion', () async {
      const inputPath = 'test/local_shadow_consumer.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, r'''
Object get $BoundaryForyFieldAccess => Object();

@ForyStruct()
class Child extends Boundary {}
''', directives: "import 'private_provider.dart';"),
        additionalAssets: <String, String>{
          'fory|test/private_provider.dart': providerSource,
        },
        message: 'collides with',
      );
    });

    test(
      'rejects an import prefix shadowing the unprefixed companion',
      () async {
        const inputPath = 'test/prefix_shadow_consumer.dart';
        await _expectGenerationError(
          inputPath: inputPath,
          source: _librarySource(
            inputPath,
            '''
@ForyStruct()
class Child extends Boundary {}
''',
            directives: r'''
import 'private_provider.dart';
import 'unrelated.dart' as $BoundaryForyFieldAccess;
''',
          ),
          additionalAssets: <String, String>{
            'fory|test/private_provider.dart': providerSource,
            'fory|test/unrelated.dart': 'class Unrelated {}',
          },
          message: 'collides with',
        );
      },
    );

    test(
      'uses a prefix when a local value shadows the unprefixed name',
      () async {
        const inputPath = 'test/local_shadow_prefixed_consumer.dart';
        final output = await _generate(
          inputPath: inputPath,
          source: _librarySource(inputPath, r'''
Object get $BoundaryForyFieldAccess => Object();

@ForyStruct()
class Child extends p.Boundary {}
''', directives: "import 'private_provider.dart' as p;"),
          additionalAssets: <String, String>{
            'fory|test/private_provider.dart': providerSource,
          },
        );

        expect(output, contains(r'p.$BoundaryForyFieldAccess.$g'));
      },
    );

    test('rejects a locally shadowed boundary namespace', () async {
      const providerPath = 'test/shadowed_boundary_provider.dart';
      const middlePath = 'test/shadowed_boundary_middle.dart';
      const childPath = 'test/shadowed_boundary_child.dart';
      final provider = _providerSource(inputPath: providerPath);
      await _expectGenerationError(
        inputPath: childPath,
        source: _librarySource(childPath, '''
class Boundary {}

@ForyStruct()
class Child extends PublicSubclass {}
''', directives: "import 'shadowed_boundary_middle.dart';"),
        additionalAssets: <String, String>{
          'fory|$providerPath': provider,
          'fory|$middlePath': '''
import 'shadowed_boundary_provider.dart';
export 'shadowed_boundary_provider.dart';

class PublicSubclass extends Boundary {}
''',
        },
        message:
            'generated companion is not visible through the child library '
            'imports and re-exports',
      );
    });

    test('uses a public alias without importing its underlying type', () async {
      const providerPath = 'test/public_alias_provider.dart';
      const childPath = 'test/public_alias_consumer.dart';
      final provider = _librarySource(providerPath, '''
typedef PublicAlias = PublicValue;

@ForyStruct(exposePrivateFields: true)
abstract class AliasBoundary {
  PublicAlias? _value;
}
''', directives: "import 'public_alias_value.dart';");
      final output = await _generate(
        inputPath: childPath,
        source: _librarySource(childPath, '''
@ForyStruct()
class AliasChild extends AliasBoundary {}
''', directives: "import 'public_alias_provider.dart';"),
        additionalAssets: <String, String>{
          'fory|$providerPath': provider,
          'fory|test/public_alias_value.dart': 'class PublicValue {}',
        },
      );

      expect(output, contains('final PublicAlias? __valueValue'));
      expect(output, contains(r'$AliasBoundaryForyFieldAccess.$g'));
    });
  });

  group('public signature validation', () {
    test('rejects a private nominal field type', () async {
      const inputPath = 'test/private_nominal.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
class _Hidden {}

@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  _Hidden? _value;
}
'''),
        message: 'would expose private nominal type _Hidden',
      );
    });

    test('rejects a nested private nominal field type', () async {
      const inputPath = 'test/nested_private_nominal.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
class _Hidden {}

@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  List<_Hidden> _values = <_Hidden>[];
}
'''),
        message: 'would expose private nominal type _Hidden',
      );
    });

    test('rejects a private generic bound', () async {
      const inputPath = 'test/private_bound.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
class _Hidden {}

@ForyStruct(exposePrivateFields: true)
class Boundary<T extends _Hidden> {
  T? _value;
}
'''),
        message: 'would expose private nominal type _Hidden',
      );
    });

    test('expands a private nonnominal alias to its public type', () async {
      const inputPath = 'test/private_alias.dart';
      final output = await _generate(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
typedef _Count = int;

@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  _Count _count = 0;
}
'''),
      );

      expect(
        output,
        matches(RegExp(r'static int \$g[0-9a-f]{16}\(Boundary value\)')),
      );
      expect(output, isNot(contains('static _Count ')));
    });

    test('rejects record signatures unsupported by ordinary structs', () async {
      const inputPath = 'test/record_signature.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  (int,) _value = (0,);
}
'''),
        message: 'ordinary ForyStruct fields do not support that type',
      );
    });

    test('rejects nominal Function signatures', () async {
      const inputPath = 'test/function_signature.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  Function _callback = () {};
}
'''),
        message: 'Companion signatures must be exact, public, and non-callback',
      );
    });

    test('rejects Null signatures unsupported by ordinary structs', () async {
      const inputPath = 'test/null_signature.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  Null _value;
}
'''),
        message: 'ordinary ForyStruct fields do not support that type',
      );
    });

    test('rejects callback signatures instead of adding a carrier', () async {
      const inputPath = 'test/private_callback.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, '''
typedef _Callback = int Function(int);

@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  _Callback _callback = _identity;
}

int _identity(int value) => value;
'''),
        message: 'Companion signatures must be exact, public, and non-callback',
      );
    });
  });

  group('exact storage and naming', () {
    test('rejects hiding after the authorized boundary', () async {
      const providerPath = 'test/hiding_provider.dart';
      const childPath = 'test/hiding_child.dart';
      final providerSource = _librarySource(providerPath, '''
class _Storage {
  int _secret = 0;
}

@ForyStruct(exposePrivateFields: true)
abstract class Boundary extends _Storage {}

class LaterBoundary extends Boundary {
  @override
  int get _secret => 1;

  @override
  set _secret(int value) {}
}
''');
      await _expectGenerationError(
        inputPath: childPath,
        source: _librarySource(childPath, '''
@ForyStruct()
class Child extends LaterBoundary {}
''', directives: "import 'hiding_provider.dart';"),
        additionalAssets: <String, String>{
          'fory|$providerPath': providerSource,
        },
        message: 'does not expose that exact slot through its effective getter',
      );
    });

    test('rejects an ignored hider after the authorized boundary', () async {
      const providerPath = 'test/ignored_hiding_provider.dart';
      const childPath = 'test/ignored_hiding_child.dart';
      final providerSource = _librarySource(providerPath, '''
@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  int _secret = 0;
}

class LaterBoundary extends Boundary {
  @ForyField(ignore: true)
  int _secret = 1;
}
''');
      await _expectGenerationError(
        inputPath: childPath,
        source: _librarySource(childPath, '''
@ForyStruct()
class Child extends LaterBoundary {}
''', directives: "import 'ignored_hiding_provider.dart';"),
        additionalAssets: <String, String>{
          'fory|$providerPath': providerSource,
        },
        message: 'does not expose that exact slot through its effective getter',
      );
    });

    test('rejects a user declaration colliding with helper name', () async {
      const inputPath = 'test/helper_collision.dart';
      await _expectGenerationError(
        inputPath: inputPath,
        source: _librarySource(inputPath, r'''
abstract final class $BoundaryForyFieldAccess {}

@ForyStruct(exposePrivateFields: true)
abstract class Boundary {
  int _secret = 0;
}
'''),
        message: 'collides with an existing top-level declaration',
      );
    });
  });
}
