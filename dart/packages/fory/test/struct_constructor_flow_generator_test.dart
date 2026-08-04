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

part 'struct_constructor_flow_fixture.fory.dart';
''';

Future<void> _expectGenerationOutput({
  required String source,
  required Matcher output,
}) async {
  await expectForyGenerationOutput(
    inputPath: 'test/struct_constructor_flow_fixture.dart',
    source: '$_fixtureHeader\n$source',
    output: output,
  );
}

Future<void> _expectGenerationError({
  required String source,
  required String message,
}) async {
  await expectForyGenerationError(
    inputPath: 'test/struct_constructor_flow_fixture.dart',
    source: '$_fixtureHeader\n$source',
    message: message,
  );
}

void main() {
  group('accepted identity flow', () {
    test('field formal', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class FieldFormalValue {
  final int value;

  FieldFormalValue(this.value);
}
''',
        output: contains('final value = FieldFormalValue(_valueValue);'),
      );
    });

    test('multi-level super formal', () async {
      await _expectGenerationOutput(
        source: '''
class SuperFormalBase<T> {
  final T value;

  SuperFormalBase(this.value);
}

class SuperFormalMiddle<T> extends SuperFormalBase<T> {
  SuperFormalMiddle(super.value);
}

@ForyStruct()
final class SuperFormalLeaf extends SuperFormalMiddle<int> {
  SuperFormalLeaf(super.value);
}
''',
        output: allOf(
          contains('final int _valueValue'),
          contains('final value = SuperFormalLeaf(_valueValue);'),
        ),
      );
    });

    test('renamed explicit initializer', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class RenamedInitializerValue {
  final int stored;

  RenamedInitializerValue(int decoded) : stored = decoded;
}
''',
        output: contains(
          'final value = RenamedInitializerValue(_storedValue);',
        ),
      );
    });

    test('named super constructor and argument', () async {
      await _expectGenerationOutput(
        source: '''
class NamedSuperBase {
  final int stored;

  NamedSuperBase.named({required this.stored});
}

@ForyStruct()
final class NamedSuperChild extends NamedSuperBase {
  NamedSuperChild({required int decoded})
    : super.named(stored: decoded);
}
''',
        output: contains(
          'final value = NamedSuperChild(decoded: _storedValue);',
        ),
      );
    });

    test('redirect', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class RedirectedValue {
  final int stored;

  RedirectedValue(int decoded) : this.named(decoded);

  RedirectedValue.named(int forwarded) : stored = forwarded;
}
''',
        output: contains('final value = RedirectedValue(_storedValue);'),
      );
    });

    test('parenthesized direct reference', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class ParenthesizedValue {
  final int stored;

  ParenthesizedValue(int decoded) : stored = (((decoded)));
}
''',
        output: contains('final value = ParenthesizedValue(_storedValue);'),
      );
    });

    test('one final and writable destination', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class FinalAndWritableValue {
  final int fixed;
  int mutable;

  FinalAndWritableValue(int decoded)
    : fixed = decoded,
      mutable = decoded;
}
''',
        output: allOf(
          contains('final value = FinalAndWritableValue(_fixedValue);'),
          contains('value.mutable = _mutableValue;'),
        ),
      );
    });

    test('passes an optional writable identity flow', () async {
      await _expectGenerationOutput(
        source: '''
class OptionalMutableBase {
  int mutable;

  OptionalMutableBase([int decoded = 0]) : mutable = decoded;
}

@ForyStruct()
final class OptionalMutableChild extends OptionalMutableBase {
  final int fixed;

  OptionalMutableChild(this.fixed, [int decoded = 0]) : super(decoded);
}
''',
        output: allOf(
          contains(
            'final value = OptionalMutableChild('
            '_fixedValue, _mutableValue);',
          ),
          isNot(contains('value.mutable = _mutableValue;')),
        ),
      );
    });

    test('analyzes optional flow for an all-writable struct', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class AllWritableOptionalValue {
  int value;

  @ForyField(ignore: true)
  final int observedAtConstruction;

  AllWritableOptionalValue([int decoded = 0])
    : value = decoded,
      observedAtConstruction = decoded;
}
''',
        output: allOf(
          contains('final value = AllWritableOptionalValue(_valueValue);'),
          isNot(contains('value.value = _valueValue;')),
        ),
      );
    });

    test('keeps an unbound optional constructor mutable', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class UnboundOptionalSelf {
  UnboundOptionalSelf({int ignored = 0});

  @ForyField(ref: true)
  UnboundOptionalSelf? next;
}
''',
        output: allOf(
          contains('final value = UnboundOptionalSelf();'),
          contains('context.reference(value);'),
          isNot(contains('ignored:')),
        ),
      );
    });

    test('omits an unbound optional named parameter', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class OptionalNamedValue {
  final int value;

  OptionalNamedValue({int ignored = 0, this.value = 0});
}
''',
        output: allOf(
          contains('final value = OptionalNamedValue(value: _valueValue);'),
          isNot(contains('ignored:')),
        ),
      );
    });

    test('keeps generic constructor specializations separate', () async {
      await _expectGenerationOutput(
        source: '''
class GenericBase<T> {
  final T value;

  GenericBase(T decoded) : value = decoded;
}

@ForyStruct()
final class GenericIntChild extends GenericBase<int> {
  GenericIntChild(int decoded) : super(decoded);
}

@ForyStruct()
final class GenericStringChild extends GenericBase<String> {
  GenericStringChild(String decoded) : super(decoded);
}
''',
        output: allOf(
          contains('final int _valueValue'),
          contains('final String _valueValue'),
          contains('final value = GenericIntChild(_valueValue);'),
          contains('final value = GenericStringChild(_valueValue);'),
        ),
      );
    });

    test('writes only unbound mutable fields', () async {
      await _expectGenerationOutput(
        source: '''
@ForyStruct()
final class CanonicalPostWrites {
  int zeta;
  int beta = 0;
  int alpha = 0;

  CanonicalPostWrites(this.zeta);
}
''',
        output: predicate<String>((generated) {
          final alphaWrite = generated.indexOf('value.alpha = _alphaValue;');
          final betaWrite = generated.indexOf('value.beta = _betaValue;');
          return alphaWrite >= 0 &&
              betaWrite >= 0 &&
              alphaWrite < betaWrite &&
              !generated.contains('value.zeta = _zetaValue;');
        }, 'writes unbound mutable fields in canonical schema order'),
      );
    });
  });

  group('rejected identity flow', () {
    test('same-name unused parameter', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class SameNameUnusedValue {
  int value = 0;

  SameNameUnusedValue(int value);
}
''',
        message: 'name equality is not constructor identity proof',
      );
    });

    test('function call', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class CallValue {
  final int value;

  CallValue(int decoded) : value = identity(decoded);

  static int identity(int value) => value;
}
''',
        message: 'transforms or does not directly reference',
      );
    });

    test('operator', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class OperatorValue {
  final int value;

  OperatorValue(int decoded) : value = decoded + 0;
}
''',
        message: 'transforms or does not directly reference',
      );
    });

    test('cast', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class CastValue {
  final int value;

  CastValue(Object decoded) : value = decoded as int;
}
''',
        message: 'transforms or does not directly reference',
      );
    });

    test('null assertion', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class NullAssertionValue {
  final int value;

  NullAssertionValue(int? decoded) : value = decoded!;
}
''',
        message: 'transforms or does not directly reference',
      );
    });

    test('constant', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class ConstantValue {
  final int value;

  ConstantValue(int decoded) : value = 1;
}
''',
        message: 'transforms or does not directly reference',
      );
    });

    test('constructor body assignment', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class BodyAssignmentValue {
  late final int value;

  BodyAssignmentValue(int decoded) {
    value = decoded;
  }
}
''',
        message: 'is not initialized from a parameter',
      );
    });

    test('final declaration initializer', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class FinalInitializerValue {
  final int value = 1;

  FinalInitializerValue();
}
''',
        message: 'has a declaration initializer',
      );
    });

    test('late-final declaration initializer', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class LateFinalInitializerValue {
  late final int value = 1;

  LateFinalInitializerValue();
}
''',
        message: 'has a declaration initializer',
      );
    });

    test('one root for multiple final fields', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class AmbiguousFinalValue {
  final int first;
  final int second;

  AmbiguousFinalValue(int decoded)
    : first = decoded,
      second = decoded;
}
''',
        message: 'initializes multiple non-writable fields',
      );
    });

    test('required root with ambiguous writable fields', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class AmbiguousWritableValue {
  int first;
  int second;

  AmbiguousWritableValue(int decoded)
    : first = decoded,
      second = decoded;
}
''',
        message: 'has multiple writable identity-flow sources',
      );
    });

    test('optional root with ambiguous writable fields', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class AmbiguousOptionalValue {
  final int fixed;
  int first;
  int second;

  AmbiguousOptionalValue(this.fixed, [int decoded = 0])
    : first = decoded,
      second = decoded;
}
''',
        message: 'has multiple writable identity-flow sources',
      );
    });

    test('required parameter without a field source', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class MissingRequiredSourceValue {
  int stored = 0;

  MissingRequiredSourceValue(int decoded);
}
''',
        message: 'has no serialized field connected by an exact',
      );
    });

    test('optional positional gap', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class OptionalGapValue {
  final int value;

  OptionalGapValue([int ignored = 0, this.value = 0]);
}
''',
        message: 'after an omitted optional positional parameter',
      );
    });

    test('reference-tracked self path', () async {
      await _expectGenerationError(
        source: '''
@ForyStruct()
final class SelfReferenceValue {
  @ForyField(ref: true)
  final SelfReferenceValue? next;

  SelfReferenceValue(this.next);
}
''',
        message: 'cannot bind reference-tracked self paths',
      );
    });
  });
}
