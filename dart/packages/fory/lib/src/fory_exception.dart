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

import 'package:fory/src/codegen/rules/code_rules.dart';
import 'package:fory/src/const/types.dart';
import 'package:meta/meta_meta.dart';
import 'package:fory/src/const/meta_string_const.dart';

abstract class ForyException extends Error {
  ForyException();

  void giveExceptionMessage(StringBuffer buf) {}

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

abstract class DeserializationException extends ForyException {
  final String? _where;

  DeserializationException([this._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    if (_where != null) {
      buf.write('where: ');
      buf.writeln(_where);
    }
  }
}

class DeserializationConflictException extends DeserializationException {
  final String _readSetting;
  final String _nowForySetting;

  DeserializationConflictException(this._readSetting, this._nowForySetting,
      [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('the fory instance setting: ');
    buf.writeln(_nowForySetting);
    buf.write('while the read setting: ');
    buf.writeln(_readSetting);
  }
}

class UnsupportedFeatureException extends DeserializationException {
  final Object _read;
  final List<Object> _supported;
  final String _whatFeature;

  UnsupportedFeatureException(this._read, this._supported, this._whatFeature,
      [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('unsupported ');
    buf.write(_whatFeature);
    buf.write(' for type: ');
    buf.writeln(_read);
    buf.write('supported: ');
    buf.writeAll(_supported, ', ');
    buf.write('\n');
  }
}

class DeserializationRangeException extends ForyException {
  final int index;
  final List<Object> candidates;

  DeserializationRangeException(
    this.index,
    this.candidates,
  );

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('the index $index is out of range, the candidates are: ');
    buf.write('[');
    buf.writeAll(candidates, ', ');
    buf.write(']\n');
    buf.write('This data may have inconsistencies on the other side');
  }
}

class InvalidParamException extends DeserializationException {
  final String _invalidParam;
  final String _validParams;

  InvalidParamException(this._invalidParam, this._validParams, [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('the invalid param: ');
    buf.writeln(_invalidParam);
    buf.write('while the valid params: ');
    buf.writeln(_validParams);
  }
}

class ForyMismatchException extends DeserializationException {
  final Object readValue;
  final Object expected;
  final String specification;

  ForyMismatchException(
    this.readValue,
    this.expected,
    this.specification,
  );

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('ForyMismatchException: ');
    buf.write(specification);
    buf.write('\nread value: ');
    buf.write(readValue);
    buf.write(' ,while expected: ');
    buf.write(expected);
    buf.write('\n');
  }
}

class UnsupportedTypeException extends ForyException {
  final ObjType _objType;

  UnsupportedTypeException(
    this._objType,
  );

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('unsupported type: ');
    buf.writeln(_objType);
  }
}

abstract class SerializationException extends ForyException {
  final String? _where;

  SerializationException([this._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    if (_where != null) {
      buf.write('where: ');
      buf.writeln(_where);
    }
  }
}

class TypeIncompatibleException extends SerializationException {
  final ObjType _specified;
  final String _reason;

  TypeIncompatibleException(this._specified, this._reason, [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('the specified type: ');
    buf.writeln(_specified);
    buf.write('while the reason: ');
    buf.writeln(_reason);
  }
}

class SerializationRangeException extends SerializationException {
  final ObjType _specified;
  final num _yourValue;

  SerializationRangeException(this._specified, this._yourValue, [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('the specified type: ');
    buf.writeln(_specified);
    buf.write('while your value: ');
    buf.writeln(_yourValue);
  }
}

class SerializationConflictException extends SerializationException {
  final String _setting;
  final String _but;

  SerializationConflictException(this._setting, this._but, [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('the setting: ');
    buf.writeln(_setting);
    buf.write('while: ');
    buf.writeln(_but);
  }
}

class UnregisteredTagException extends ForyException {
  final String _tag;

  UnregisteredTagException(this._tag);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Unregistered tag: ');
    buf.writeln(_tag);
  }
}

class UnregisteredTypeException extends ForyException {
  final Object _type;

  UnregisteredTypeException(this._type);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Unregistered type: ');
    buf.writeln(_type);
  }
}

class DuplicatedTagRegistrationException extends ForyException {
  final String _tag;
  final Type _tagType;
  final Type _newType;

  DuplicatedTagRegistrationException(this._tag, this._tagType, this._newType);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Duplicate registration for tag: ');
    buf.writeln(_tag);
    buf.write('\nThis tag is already registered for type: ');
    buf.writeln(_tagType);
    buf.write('\nBut you are now trying to register it for type: ');
    buf.writeln(_newType);
  }
}

class DuplicatedTypeRegistrationException extends ForyException {
  final Type _forType;
  final Object _newRegistration;

  DuplicatedTypeRegistrationException(this._forType, this._newRegistration);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Duplicate registration for type: ');
    buf.writeln(_forType);
    buf.write('\nBut you try to register it again with: ');
    buf.writeln(_newRegistration);
  }
}

class DuplicatedUserTypeIdRegistrationException extends ForyException {
  final int _userTypeId;
  final Type _registeredType;
  final Type _newType;

  DuplicatedUserTypeIdRegistrationException(
      this._userTypeId, this._registeredType, this._newType);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Duplicate registration for user type id: ');
    buf.writeln(_userTypeId);
    buf.write('\nThis user type id is already registered for type: ');
    buf.writeln(_registeredType);
    buf.write('\nBut you are now trying to register it for type: ');
    buf.writeln(_newType);
  }
}

class RegistrationArgumentException extends ForyException {
  final Object? _arg;

  RegistrationArgumentException(this._arg);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Invalid registration argument: ');
    buf.writeln(_arg);
    buf.writeln('Expected `String` tag or `int` user type id.');
  }
}

class InvalidDataException extends ForyException {
  final String message;

  InvalidDataException(this.message);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    buf.write(message);
  }
}

abstract class ForyCodegenException extends ForyException {
  final String? _where;
  ForyCodegenException([this._where]);

  /// will generate warning and error location
  @override
  void giveExceptionMessage(StringBuffer buf) {
    buf.write('''[FORY]: Analysis error detected!
You need to make sure your codes don't contain any grammar error itself.
And review the error messages below, correct the issues, and then REGENERATE the code.
''');
    if (_where != null && _where.isNotEmpty) {
      buf.write('where: ');
      buf.write(_where);
      buf.write('\n');
    }
  }
}

class ClassLevelException extends ForyCodegenException {
  final String _libPath;
  final String _className;

  ClassLevelException(this._libPath, this._className, [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('related class: ');
    buf.write(_libPath);
    buf.write('@');
    buf.write(_className);
    buf.write('\n');
  }

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

abstract class FieldException extends ForyConstraintViolation {
  final String _libPath;
  final String _className;
  final List<String> _invalidFields;

  FieldException(
      this._libPath, this._className, this._invalidFields, super._constraint,
      [super.where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('related class: ');
    buf.write(_libPath);
    buf.write('@');
    buf.write(_className);
    buf.write('\n');
    buf.write('invalidFields: ');
    buf.writeAll(_invalidFields, ', ');
    buf.write('\n');
  }

  @override
  String toString() {
    StringBuffer buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

abstract class ForyConstraintViolation extends ForyCodegenException {
  final String _constraint;

  ForyConstraintViolation(this._constraint, [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('constraint: ');
    buf.write(_constraint);
    buf.write('\n');
  }
}

class CircularIncapableRisk extends ForyConstraintViolation {
  final String libPath;
  final String className;

  CircularIncapableRisk(
    this.libPath,
    this.className,
  ) : super(
          CodeRules.circularReferenceIncapableRisk,
        );

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('related class: ');
    buf.write(libPath);
    buf.write('@');
    buf.write(className);
    buf.write('\n');
  }

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

class InformalConstructorParamException extends ClassLevelException {
  final List<String> _invalidParams;

  // There is no need to add the reason field, because the reason is actually just invalidParams
  InformalConstructorParamException(
      String libPath, String className, this._invalidParams,
      [String? where])
      : super(libPath, className, where);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write(CodeRules.consParamsOnlySupportThisAndSuper);
    buf.write('invalidParams: ');
    buf.writeAll(_invalidParams, ', ');
    buf.write('\n');
  }

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

class FieldOverridingException extends FieldException {
  FieldOverridingException(
      String libPath, String className, List<String> invalidFields,
      [String? where])
      : super(libPath, className, invalidFields,
            CodeRules.unsupportFieldOverriding, where);
}

class NoUsableConstructorException extends ForyCodegenException {
  final String libPath;
  final String className;
  final String reason;

  NoUsableConstructorException(this.libPath, this.className, this.reason)
      : super('$libPath@$className');
}

class CodegenUnsupportedTypeException extends ForyCodegenException {
  final String clsLibPath;
  final String clsName;
  final String fieldName;

  final String typeScheme;
  final String typePath;
  final String typeName;

  CodegenUnsupportedTypeException(
    this.clsLibPath,
    this.clsName,
    this.fieldName,
    this.typeScheme,
    this.typePath,
    this.typeName,
  ) : super('$clsLibPath@$clsName');

  /// will generate warning and error location
  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Unsupported type: ');
    buf.write(typeScheme);
    buf.write(':');
    buf.write(typePath);
    buf.write('@');
    buf.write(typeName);
    buf.write('\n');
  }

  @override
  String toString() {
    StringBuffer buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

class ConstraintViolationException extends FieldException {
  ConstraintViolationException(
    String libPath,
    String className,
    String fieldName,
    String constraint, [
    String? where,
  ]) : super(libPath, className, [fieldName], constraint, where);
}


enum FieldAccessErrorType {
  noWayToAssign(
      "This field needs to be assigned a value because it's includedFromFory, but it's not a constructor parameter and can't be assigned via a setter."),
  noWayToGet(
      "This field needs to be read because it's includedFromFory, but it's not public and it can't be read via a getter."),
  notIncludedButConsDemand(
      "This field is included in the constructor, but it's not includedFromFory. ");

  final String warning;

  const FieldAccessErrorType(this.warning);
}

class FieldAccessException extends FieldException {
  final FieldAccessErrorType errorType;

  FieldAccessException(
    String libPath,
    String clsName,
    List<String> fieldNames,
    this.errorType,
  ) : super(
          libPath,
          clsName,
          fieldNames,
          errorType.warning,
        );
}

abstract class AnnotationException extends ForyCodegenException {
  AnnotationException(super._where);
}

class InvalidClassTagException extends ForyCodegenException {
  final List<String>? _classesWithEmptyTag;
  final List<String>? _classesWithTooLongTag;
  final Map<String, List<String>>? _repeatedTags;

  InvalidClassTagException(this._classesWithEmptyTag,
      this._classesWithTooLongTag, this._repeatedTags,
      [super._where]) {
    assert(_classesWithEmptyTag != null ||
        _repeatedTags != null ||
        _classesWithTooLongTag != null);
  }

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    if (_classesWithEmptyTag != null) {
      buf.write('Classes with empty tag:');
      buf.writeAll(_classesWithEmptyTag, ', ');
      buf.write('\n');
    }

    if (_classesWithTooLongTag != null) {
      buf.write('Classes with too long tag (should be less than ');
      buf.write(MetaStringConst.metaStrMaxLen);
      buf.write('):');
      buf.writeAll(_classesWithTooLongTag, ', ');
      buf.write('\n');
    }

    if (_repeatedTags != null) {
      buf.write('Classes with repeated tags:');
      for (String c in _repeatedTags.keys) {
        buf.write(c);
        buf.write(': ');
        buf.writeAll(_repeatedTags[c]!, ', ');
        buf.write('\n');
      }
    }
  }

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

class ConflictAnnotationException extends AnnotationException {
  final String _targetAnnotation;
  final String _conflictAnnotation;

  ConflictAnnotationException(this._targetAnnotation, this._conflictAnnotation,
      [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write(
        'The annotation $_targetAnnotation conflicts with $_conflictAnnotation.');
    buf.write('\n');
  }

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

class DuplicatedAnnotationException extends AnnotationException {
  final String _annotation;
  final String _displayName;

  DuplicatedAnnotationException(this._annotation, this._displayName,
      [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write(_displayName);
    buf.write(' has multiple ');
    buf.write(_annotation);
    buf.write(' annotations.');
    buf.write('\n');
  }

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

class CodegenUnregisteredTypeException extends AnnotationException {
  final String _libPath;
  final String _clsName;

  final String _annotation;

  CodegenUnregisteredTypeException(
      this._libPath, this._clsName, this._annotation,
      [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Unregistered type: ');
    buf.write(_libPath);
    buf.write('@');
    buf.write(_clsName);
    buf.write('\nit should be registered with the annotation: ');
    buf.write(_annotation);
  }

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

class InvalidAnnotationTargetException extends AnnotationException {
  final String _annotation;
  final String _theTarget;
  final List<TargetKind> _supported;

  InvalidAnnotationTargetException(
      this._annotation, this._theTarget, this._supported,
      [super._where]);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Unsupported target for annotation: ');
    buf.writeln(_annotation);
    buf.write('Target: ');
    buf.writeln(_theTarget);
    buf.write('Supported targets: ');
    buf.writeAll(_supported, ', ');
  }

  @override
  String toString() {
    final buf = StringBuffer();
    giveExceptionMessage(buf);
    return buf.toString();
  }
}

