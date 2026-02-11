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

import 'package:fory/src/codegen/exception/fory_codegen_exception.dart';
import 'package:fory/src/codegen/rules/code_rules.dart';

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

class UnsupportedTypeException extends ForyCodegenException {
  final String clsLibPath;
  final String clsName;
  final String fieldName;

  final String typeScheme;
  final String typePath;
  final String typeName;

  UnsupportedTypeException(
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
