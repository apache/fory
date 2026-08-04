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

const String _fixtureHeader = '''
import 'package:fory/src/annotation/fory_field.dart';
import 'package:fory/src/annotation/fory_struct.dart';
import 'package:fory/src/annotation/type_spec.dart';
import 'external_target_fixture.dart';

part 'external_serializer_fixture.fory.dart';
''';

Future<void> _expectGenerationError({
  required String targetSource,
  required String declarationSource,
  required String message,
}) async {
  await expectForyGenerationError(
    inputPath: 'test/external_serializer_fixture.dart',
    source: '$_fixtureHeader\n$declarationSource',
    additionalAssets: <String, String>{
      'fory|test/external_target_fixture.dart': targetSource,
    },
    message: message,
  );
}

Future<void> _expectGenerationOutput({
  required String targetSource,
  required String declarationSource,
  required Matcher output,
  String fixtureHeader = _fixtureHeader,
  Map<String, String> additionalAssets = const <String, String>{},
}) async {
  await expectForyGenerationOutput(
    inputPath: 'test/external_serializer_fixture.dart',
    source: '$fixtureHeader\n$declarationSource',
    additionalAssets: <String, String>{
      'fory|test/external_target_fixture.dart': targetSource,
      ...additionalAssets,
    },
    output: output,
  );
}

void main() {
  test('rejects constructor without target', () async {
    await _expectGenerationError(
      targetSource: 'class Target {}',
      declarationSource: '''
@ForyStruct(constructor: 'named')
final class Value {
  Value.named();
}
''',
      message: 'valid only when ForyStruct.target is set',
    );
  });

  test('rejects non-class target', () async {
    await _expectGenerationError(
      targetSource: 'class Target {}',
      declarationSource: '''
@ForyStruct(target: Never)
abstract final class TargetSerializer {}
''',
      message: 'must name a concrete Dart class',
    );
  });

  test('rejects carrier target', () async {
    await _expectGenerationError(
      targetSource: 'class Target {}',
      declarationSource: '''
@ForyStruct(target: List<String>)
abstract final class ListSerializer {}
''',
      message: 'owned by an existing built-in',
    );
  });

  test('rejects self target', () async {
    await _expectGenerationError(
      targetSource: 'class Target {}',
      declarationSource: '''
@ForyStruct(target: TargetSerializer)
abstract final class TargetSerializer {}
''',
      message: 'must name another class',
    );
  });

  test('rejects extension target', () async {
    await _expectGenerationError(
      targetSource: 'extension type Target(int value) {}',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {}
''',
      message: 'owned by an existing built-in',
    );
  });

  test('rejects builtin target', () async {
    await _expectGenerationError(
      targetSource: 'class Target {}',
      declarationSource: '''
@ForyStruct(target: String)
abstract final class StringSerializer {}
''',
      message: 'owned by an existing built-in',
    );
  });

  test('rejects enum target', () async {
    await _expectGenerationError(
      targetSource: 'enum Target { value }',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {}
''',
      message: 'must name a concrete Dart class',
    );
  });

  test('rejects abstract target', () async {
    await _expectGenerationError(
      targetSource: '''
abstract class Target {
  String get value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'must be a concrete constructable Dart class',
    );
  });

  test('rejects union target', () async {
    await _expectGenerationError(
      targetSource: '''
import 'package:fory/src/annotation/fory_union.dart';

@ForyUnion()
class Target {
  Target(this.value);
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'owned by an existing built-in',
    );
  });

  test('rejects declaration shape', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.value);
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
final class TargetSerializer {
  late final String value;
}
''',
      message: 'must be declared abstract final',
    );
  });

  test('rejects generic declaration', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.value);
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer<T> {
  late final String value;
}
''',
      message: 'cannot declare type parameters',
    );
  });

  test('rejects unresolved target type parameter', () async {
    await _expectGenerationError(
      targetSource: '''
class Target<T> {
  Target(this.value);
  final T value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target<T>)
abstract final class TargetSerializer<T> {
  late final T value;
}
''',
      message: 'must be a closed type',
    );
  });

  test('rejects non-schema field shape', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.value);
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late String value;
}
''',
      message: 'must be a late final field without an initializer',
    );
  });

  test('rejects schema field initializer', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.value);
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value = '';
}
''',
      message: 'must be a late final field without an initializer',
    );
  });

  test('validates ignored schema field shape', () async {
    await _expectGenerationError(
      targetSource: 'class Target {}',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  @ForyField(ignore: true)
  late String hidden;
}
''',
      message: 'must be a late final field without an initializer',
    );
  });

  test('rejects duplicate target', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.value);
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class FirstSerializer {
  late final String value;
}

@ForyStruct(target: Target)
abstract final class SecondSerializer {
  late final String value;
}
''',
      message: 'both target Target',
    );
  });

  test('rejects inaccessible getter', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this._value);
  final String _value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String _value;
}
''',
      message: 'must expose an accessible instance getter named _value',
    );
  });

  test('rejects getter type mismatch', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.value);
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final int value;
}
''',
      message: 'types must match exactly',
    );
  });

  test('rejects getter nullability mismatch', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.value);
  final String? value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'types must match exactly',
    );
  });

  test('rejects inaccessible constructor', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target._();
  String get value => '';
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'has no accessible unnamed constructor',
    );
  });

  test('rejects missing named constructor', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target();
  String get value => '';
}
''',
      declarationSource: '''
@ForyStruct(target: Target, constructor: 'missing')
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'has no accessible .missing',
    );
  });

  test('rejects empty constructor selection', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target();
}
''',
      declarationSource: '''
@ForyStruct(target: Target, constructor: '')
abstract final class TargetSerializer {}
''',
      message: 'must name a public named generative constructor',
    );
  });

  test('rejects private constructor selection', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target._hidden();
  String get value => '';
}
''',
      declarationSource: '''
@ForyStruct(target: Target, constructor: '_hidden')
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'must name a public named generative constructor',
    );
  });

  test('rejects factory constructor', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  factory Target() => Target._();
  Target._();
  String get value => '';
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'must be generative, not a factory',
    );
  });

  test('rejects missing required constructor field', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target({required String hidden}) : value = hidden;
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'Required constructor parameter hidden',
    );
  });

  test('rejects constructor type mismatch', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(Object value) : value = value as String;
  final String value;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'Constructor parameter value',
    );
  });

  test('rejects optional positional gap', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target([String first = '', this.second = '']);
  final String second;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String second;
}
''',
      message: 'after an omitted optional positional parameter',
    );
  });

  test('uses setter after optional positional gap', () async {
    await _expectGenerationOutput(
      targetSource: '''
class Target {
  Target([this.first = '', String ignored = '', this.third = '']);
  final String first;
  String third;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String first;
  late final String third;
}
''',
      output: allOf(
        contains('final value = Target(_firstValue);'),
        contains('value.third = _thirdValue;'),
      ),
    );
  });

  test('accounts for external target storage without serializing it', () async {
    await _expectGenerationOutput(
      targetSource: '''
mixin TargetMixin {
  final bool mixed = false;
}

class Base<T> {
  Base(this.inherited);

  final T inherited;
}

class Target extends Base<String> with TargetMixin {
  Target(this.value, String inherited) : super(inherited);

  final int value;
  final String omitted = 'omitted';
  final Object _hidden = Object();
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final int value;
  late final String inherited;

  @ForyField(ignore: true)
  late final Object _hidden;
}
''',
      output: allOf(
        contains('context.reserveGraphMemory(44);'),
        contains("identifier: 'value',"),
        contains("identifier: 'inherited',"),
        isNot(contains("identifier: 'omitted',")),
        isNot(contains('_hidden')),
        isNot(contains("identifier: 'mixed',")),
      ),
    );
  });

  test('renders nullable prefixed re-exported types', () async {
    await _expectGenerationOutput(
      targetSource: '''
class User {
  User(this.name);
  final String name;
}

typedef UserAlias = User;

class Box<T> {
  Box(this.value);
  final T? value;
}
''',
      additionalAssets: const <String, String>{
        'fory|test/external_target_barrel.dart':
            "export 'external_target_fixture.dart' show Box, UserAlias;",
      },
      fixtureHeader: '''
import 'package:fory/src/annotation/fory_struct.dart';
import 'external_target_barrel.dart' as api;

part 'external_serializer_fixture.fory.dart';
''',
      declarationSource: '''
@ForyStruct(target: api.Box<api.UserAlias>)
abstract final class UserBoxSerializer {
  late final api.UserAlias? value;
}
''',
      output: allOf(
        contains('GeneratedStructSchema<api.Box<api.UserAlias>>'),
        contains('type: api.UserAlias,'),
        contains('api.UserAlias? _valueValue'),
        contains('final value = api.Box<api.UserAlias>(_valueValue);'),
      ),
    );
  });

  test('rejects missing post-construction setter', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target({required this.name}) : score = 0;
  final String name;
  final int score;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String name;
  late final int score;
}
''',
      message: 'has no accessible exact-type setter',
    );
  });

  test('rejects setter type mismatch', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target();
  String _value = '';
  String get value => _value;
  set value(Object value) {
    _value = value as String;
  }
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  late final String value;
}
''',
      message: 'Getter, setter, and declaration types must match exactly',
    );
  });

  test('rejects constructor self reference', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.next);
  final Target? next;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  @ForyField(ref: true)
  late final Target? next;
}
''',
      message: 'cannot bind reference-tracked self paths',
    );
  });

  test('rejects nested constructor self reference', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.children);
  final List<Target> children;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  @ListField(element: DeclaredType(ref: true))
  late final List<Target> children;
}
''',
      message: 'cannot bind reference-tracked self paths',
    );
  });

  test('rejects set constructor self reference', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.children);
  final Set<Target> children;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  @SetField(element: DeclaredType(ref: true))
  late final Set<Target> children;
}
''',
      message: 'cannot bind reference-tracked self paths',
    );
  });

  test('rejects map-key constructor self reference', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.children);
  final Map<Target, String> children;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  @MapField(key: DeclaredType(ref: true))
  late final Map<Target, String> children;
}
''',
      message: 'cannot bind reference-tracked self paths',
    );
  });

  test('rejects map-value constructor self reference', () async {
    await _expectGenerationError(
      targetSource: '''
class Target {
  Target(this.children);
  final Map<String, List<Target>> children;
}
''',
      declarationSource: '''
@ForyStruct(target: Target)
abstract final class TargetSerializer {
  @MapField(value: ListType(element: DeclaredType(ref: true)))
  late final Map<String, List<Target>> children;
}
''',
      message: 'cannot bind reference-tracked self paths',
    );
  });
}
