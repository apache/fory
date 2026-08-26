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

const String _inputPath = 'test/struct_inheritance_generator_fixture.dart';

const String _fixtureHeader = '''
import 'package:fory/src/annotation/fory_field.dart';
import 'package:fory/src/annotation/fory_struct.dart';
import 'package:fory/src/annotation/type_spec.dart';

part 'struct_inheritance_generator_fixture.fory.dart';
''';

Future<void> _expectGenerationOutput({
  required String source,
  required Matcher output,
  String fixtureHeader = _fixtureHeader,
  Map<String, String> additionalAssets = const <String, String>{},
}) async {
  await expectForyGenerationOutput(
    inputPath: _inputPath,
    source: '$fixtureHeader\n$source',
    output: output,
    additionalAssets: additionalAssets,
  );
}

Future<void> _expectGenerationError({
  required String source,
  required String message,
  String fixtureHeader = _fixtureHeader,
  Map<String, String> additionalAssets = const <String, String>{},
}) async {
  await expectForyGenerationError(
    inputPath: _inputPath,
    source: '$fixtureHeader\n$source',
    message: message,
    additionalAssets: additionalAssets,
  );
}

Matcher _wireFieldsInOrder(List<String> names) {
  return predicate<String>((output) {
    var offset = 0;
    for (final name in names) {
      final next = output.indexOf("identifier: '$name',", offset);
      if (next < 0) {
        return false;
      }
      offset = next + 1;
    }
    return true;
  }, 'contains wire fields in order: ${names.join(', ')}');
}

Matcher _containsOnce(String text) {
  return predicate<String>(
    (output) => RegExp(RegExp.escape(text)).allMatches(output).length == 1,
    'contains exactly once: $text',
  );
}

void main() {
  group('ordinary hierarchy discovery', () {
    test('flattens superclass and mixin storage exactly once', () async {
      await _expectGenerationOutput(
        source: '''
abstract interface class Contract {
  Object? get interfaceOnly;
}

abstract class Grand {
  Object? grand;
  static Object? staticOnly;

  Object? get abstractOnly;
}

class Parent extends Grand {
  Object? parent;

  @override
  Object? get abstractOnly => parent;
}

mixin M1 {
  Object? m1;
}

mixin M2 {
  Object? m2;
}

@ForyStruct()
class Child extends Parent with M1, M2 implements Contract {
  Child();

  Object? child;

  @override
  Object? get interfaceOnly => child;
}
''',
        output: allOf(<Matcher>[
          _wireFieldsInOrder(const <String>[
            'child',
            'grand',
            'm1',
            'm2',
            'parent',
          ]),
          _containsOnce("identifier: 'grand',"),
          _containsOnce("identifier: 'parent',"),
          _containsOnce("identifier: 'm1',"),
          _containsOnce("identifier: 'm2',"),
          _containsOnce("identifier: 'child',"),
          contains(RegExp(r'value\.grand = _readChildGrand\(')),
          contains(RegExp(r'value\.parent = _readChildParent\(')),
          contains(RegExp(r'value\.m1 = _readChildM1\(')),
          contains(RegExp(r'value\.m2 = _readChildM2\(')),
          contains(RegExp(r'value\.child = _readChildChild\(')),
          contains('context.reserveGraphMemory(44);'),
          isNot(contains("identifier: 'interface_only',")),
          isNot(contains("identifier: 'abstract_only',")),
          isNot(contains("identifier: 'static_only',")),
        ]),
      );
    });

    test('flattens a named generic mixin application', () async {
      await _expectGenerationOutput(
        source: '''
class AliasBase<T> {
  final T baseValue;

  AliasBase(this.baseValue);
}

mixin AliasMixin<T> {
  T? mixinValue;
}

class NamedApplication<T> = AliasBase<T> with AliasMixin<T>;

@ForyStruct()
class AliasChild extends NamedApplication<int> {
  AliasChild(super.baseValue);

  int childValue = 0;
}
''',
        output: allOf(
          contains('final int _baseValueValue'),
          contains('final int? _mixinValueValue'),
          contains('final int _childValueValue'),
          contains('final value = AliasChild(_baseValueValue);'),
          contains('context.reserveGraphMemory(36);'),
        ),
      );
    });

    test('substitutes inherited generic fields in each layer', () async {
      await _expectGenerationOutput(
        source: '''
class Base<T> {
  late List<T> items;
}

class Middle<U> extends Base<U> {}

@ForyStruct()
class StringChild extends Middle<String> {
  StringChild();
}
''',
        output: allOf(
          contains("identifier: 'items',"),
          contains('type: List<String>,'),
          contains('value.items'),
          isNot(contains('type: List<T>,')),
          isNot(contains('type: List<U>,')),
        ),
      );
    });

    test('uses public storage from another library directly', () async {
      await _expectGenerationOutput(
        fixtureHeader: '''
import 'package:fory/src/annotation/fory_struct.dart';
import 'public_inheritance_parent.dart';

part 'struct_inheritance_generator_fixture.fory.dart';
''',
        additionalAssets: const <String, String>{
          'fory|test/public_inheritance_parent.dart': '''
class PublicParent {
  Object? inherited;
}
''',
        },
        source: '''
@ForyStruct()
class PublicChild extends PublicParent {
  PublicChild();
}
''',
        output: allOf(
          contains("identifier: 'inherited',"),
          contains('value.inherited'),
          isNot(contains('ForyFieldAccess')),
        ),
      );
    });

    test('rejects an ambiguous inherited field type name', () async {
      await _expectGenerationError(
        fixtureHeader: '''
import 'package:fory/src/annotation/fory_struct.dart';
import 'typed_inheritance_parent.dart';
import 'conflicting_type.dart';

part 'struct_inheritance_generator_fixture.fory.dart';
''',
        additionalAssets: const <String, String>{
          'fory|test/typed_inheritance_parent.dart': '''
class SharedValue {}

class TypedParent {
  SharedValue? inherited;
}
''',
          'fory|test/conflicting_type.dart': 'class SharedValue {}',
        },
        source: '''
@ForyStruct()
class TypedChild extends TypedParent {
  TypedChild();
}
''',
        message: 'SharedValue, which is not nameable',
      );
    });

    test('uses an unambiguous prefix for an inherited field type', () async {
      await _expectGenerationOutput(
        fixtureHeader: '''
import 'package:fory/src/annotation/fory_struct.dart';
import 'typed_inheritance_parent.dart';
import 'typed_inheritance_parent.dart' as parent;
import 'conflicting_type.dart';

part 'struct_inheritance_generator_fixture.fory.dart';
''',
        additionalAssets: const <String, String>{
          'fory|test/typed_inheritance_parent.dart': '''
class SharedValue {}

class TypedParent {
  SharedValue? inherited;
}
''',
          'fory|test/conflicting_type.dart': 'class SharedValue {}',
        },
        source: '''
@ForyStruct()
class TypedChild extends TypedParent {
  TypedChild();
}
''',
        output: allOf(
          contains('type: parent.SharedValue,'),
          contains('parent.SharedValue? _readTypedChildInherited('),
          contains('value.inherited'),
        ),
      );
    });

    test('rejects a deferred inherited field type namespace', () async {
      await _expectGenerationError(
        fixtureHeader: '''
import 'package:fory/src/annotation/fory_struct.dart';
import 'deferred_type_parent.dart';
import 'shared_value.dart' deferred as values;

part 'struct_inheritance_generator_fixture.fory.dart';
''',
        additionalAssets: const <String, String>{
          'fory|test/shared_value.dart': 'class SharedValue {}',
          'fory|test/deferred_type_parent.dart': '''
import 'shared_value.dart';

class DeferredTypeParent {
  SharedValue? inherited;
}
''',
        },
        source: '''
@ForyStruct()
class DeferredTypeChild extends DeferredTypeParent {
  DeferredTypeChild();
}
''',
        message: 'SharedValue, which is not nameable',
      );
    });

    test('uses same-library private storage directly', () async {
      await _expectGenerationOutput(
        source: '''
class PrivateParent {
  Object? _secret;
}

@ForyStruct()
class PrivateChild extends PrivateParent {
  PrivateChild();
}
''',
        output: allOf(
          contains("identifier: '_secret',"),
          contains('value._secret'),
          isNot(contains('ForyFieldAccess')),
        ),
      );
    });

    test('reconstructs same-library private final storage directly', () async {
      await _expectGenerationOutput(
        source: '''
class PrivateFinalParent {
  final int _secret;

  PrivateFinalParent(this._secret);
}

@ForyStruct()
class PrivateFinalChild extends PrivateFinalParent {
  PrivateFinalChild(int decoded) : super(decoded);
}
''',
        output: allOf(
          contains("identifier: '_secret',"),
          contains('value._secret'),
          contains('final value = PrivateFinalChild(__secretValue);'),
          isNot(contains('ForyFieldAccess')),
        ),
      );
    });

    test('filters inherited private storage before schema analysis', () async {
      await _expectGenerationOutput(
        source: '''
class PolicyBase {
  Object? _baseSecret;
  Object? inheritedPublic;
}

mixin PolicyMixin {
  @ForyField(ref: true)
  Object? _mixinSecret;

  Object? mixinPublic;
}

@ForyStruct(ignoreInheritedPrivateFields: true)
class PolicyChild extends PolicyBase with PolicyMixin {
  PolicyChild();

  Object? _childPrivate;
  Object? childPublic;
}
''',
        output: allOf(
          _wireFieldsInOrder(const <String>[
            '_child_private',
            'child_public',
            'inherited_public',
            'mixin_public',
          ]),
          contains('needsRootRef: false,'),
          isNot(contains("identifier: '_base_secret',")),
          isNot(contains("identifier: '_mixin_secret',")),
          isNot(contains('context.reference(value);')),
        ),
      );
    });

    test('does not inherit the parent omission policy', () async {
      await _expectGenerationOutput(
        source: '''
class PolicyRoot {
  Object? _rootSecret;
}

@ForyStruct(ignoreInheritedPrivateFields: true)
class PolicyParent extends PolicyRoot {
  PolicyParent();
}

@ForyStruct()
class PolicyChild extends PolicyParent {
  PolicyChild();
}
''',
        output: allOf(
          _containsOnce("identifier: '_root_secret',"),
          contains('value._rootSecret'),
        ),
      );
    });

    test('keeps strict construction after private filtering', () async {
      await _expectGenerationError(
        source: '''
class RequiredPrivateBase {
  RequiredPrivateBase(int decoded) : _secret = decoded;

  final int _secret;
}

@ForyStruct(ignoreInheritedPrivateFields: true)
class RequiredPrivateChild extends RequiredPrivateBase {
  RequiredPrivateChild(int decoded) : super(decoded);
}
''',
        message: 'has no serialized field connected by an exact',
      );
    });

    test(
      'ignores only declaration-owned fields before access checks',
      () async {
        await _expectGenerationOutput(
          fixtureHeader: '''
import 'package:fory/src/annotation/fory_struct.dart';
import 'ignored_inheritance_parent.dart';

part 'struct_inheritance_generator_fixture.fory.dart';
''',
          additionalAssets: const <String, String>{
            'fory|test/ignored_inheritance_parent.dart': '''
import 'package:fory/src/annotation/fory_field.dart';

class IgnoredParent {
  @ForyField(ignore: true)
  Object? _ignored;

  Object? visible;
}
''',
          },
          source: '''
@ForyStruct()
class IgnoreChild extends IgnoredParent {
  IgnoreChild();
}
''',
          output: allOf(
            contains("identifier: 'visible',"),
            contains('value.visible'),
            contains('context.reserveGraphMemory(32);'),
            isNot(contains('_ignored')),
            isNot(contains('ForyFieldAccess')),
          ),
        );
      },
    );

    test('rejects an unsupported inherited effective type', () async {
      await _expectGenerationError(
        source: '''
int _identity(int value) => value;

class UnsupportedParent {
  int Function(int) callback = _identity;
}

@ForyStruct()
class UnsupportedChild extends UnsupportedParent {
  UnsupportedChild();
}
''',
        message: 'has unsupported effective type int Function(int)',
      );
    });

    test(
      'ignored unsupported inherited storage bypasses type analysis',
      () async {
        await _expectGenerationOutput(
          source: '''
int _identity(int value) => value;

class IgnoredUnsupportedParent {
  @ForyField(ignore: true)
  int Function(int) callback = _identity;
}

@ForyStruct()
class IgnoredUnsupportedChild extends IgnoredUnsupportedParent {
  IgnoredUnsupportedChild();

  int value = 0;
}
''',
          output: allOf(
            contains("identifier: 'value',"),
            contains('context.reserveGraphMemory(32);'),
            isNot(contains('callback')),
          ),
        );
      },
    );
  });

  group('hierarchy validation', () {
    test('rejects hidden ancestor storage', () async {
      await _expectGenerationError(
        source: '''
class HiddenBase {
  Object? value;
}

@ForyStruct()
class HiddenChild extends HiddenBase {
  HiddenChild();

  @override
  Object? value;
}
''',
        message: 'later field or accessor hides the storage',
      );
    });

    test('rejects a repeated mixin storage application', () async {
      await _expectGenerationError(
        source: '''
class RepeatedBase {}

mixin RepeatedMixin {
  int value = 0;
}

class FirstApplication = RepeatedBase with RepeatedMixin;
class SecondApplication = FirstApplication with RepeatedMixin;

@ForyStruct()
class RepeatedChild extends SecondApplication {}
''',
        message: 'the same field declaration is applied more than once',
      );
    });

    test('rejects duplicate ids across the flattened schema', () async {
      await _expectGenerationError(
        source: '''
class IdBase {
  @ForyField(id: 7)
  int baseValue = 0;
}

@ForyStruct()
class IdChild extends IdBase {
  IdChild();

  @ForyField(id: 7)
  int childValue = 0;
}
''',
        message: 'duplicate field id 7',
      );
    });

    test('accepts the largest field id', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
class LargestFieldId {
  LargestFieldId();

  @ForyField(id: 0x1fffffff)
  int value = 0;
}
''',
        output: anything,
      );
    });

    test('rejects field ids outside the protocol range', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
class OversizedFieldId {
  OversizedFieldId();

  @ForyField(id: 0x20000000)
  int value = 0;
}
''',
        message: 'Fory field id must not exceed 536870911.',
      );
    });

    test('rejects duplicate canonical names across hierarchy layers', () async {
      await _expectGenerationError(
        source: '''
class NameBase {
  int fooBar = 0;
}

@ForyStruct()
class NameChild extends NameBase {
  NameChild();

  int foo_bar = 0;
}
''',
        message: 'duplicate canonical field name foo_bar',
      );
    });

    test('uses indexed generated names when source names collide', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
class CodegenNameCollision {
  CodegenNameCollision(this.foo, this.Foo);

  @ForyField(id: 1)
  final int foo;

  @ForyField(id: 2)
  final int Foo;
}
''',
        output: allOf(
          contains('final int _field0Value'),
          contains('final int _field1Value'),
          contains('_readCodegenNameCollisionField0('),
          contains('_readCodegenNameCollisionField1('),
          contains(
            'final value = CodegenNameCollision(_field0Value, _field1Value);',
          ),
        ),
      );
    });

    test('sorts from normalized TypeSpec nullability', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
class NormalizedSort {
  NormalizedSort();

  @ForyField(type: Int64Type(nullable: true))
  int aForcedBoxed = 0;

  int zPrimitive = 0;
}
''',
        output: allOf(
          _wireFieldsInOrder(const <String>['z_primitive', 'a_forced_boxed']),
          predicate<String>((output) {
            final fieldStart = output.indexOf("identifier: 'a_forced_boxed',");
            final fieldEnd = output.indexOf('),', fieldStart);
            if (fieldStart < 0 || fieldEnd < 0) {
              return false;
            }
            return output
                .substring(fieldStart, fieldEnd)
                .contains('nullable: true,');
          }, 'uses normalized nullable metadata for a_forced_boxed'),
        ),
      );
    });
  });

  test('keeps external target hierarchy schema explicit', () async {
    await _expectGenerationOutput(
      fixtureHeader: '''
import 'package:fory/src/annotation/fory_struct.dart';
import 'external_inheritance_target.dart';

part 'struct_inheritance_generator_fixture.fory.dart';
''',
      additionalAssets: const <String, String>{
        'fory|test/external_inheritance_target.dart': '''
class ExternalBase {
  int inherited = 0;
  int omittedBase = 0;
}

class ExternalChild extends ExternalBase {
  int omittedChild = 0;
}
''',
      },
      source: '''
@ForyStruct(target: ExternalChild)
abstract final class ExternalChildSchema {
  late final int inherited;
}
''',
      output: allOf(
        _containsOnce("identifier: 'inherited',"),
        contains('value.inherited'),
        contains('context.reserveGraphMemory(36);'),
        isNot(contains("identifier: 'omitted_base',")),
        isNot(contains("identifier: 'omitted_child',")),
      ),
    );
  });
}
