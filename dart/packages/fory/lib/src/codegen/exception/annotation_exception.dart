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

import 'package:meta/meta_meta.dart';
import 'package:fory/src/codegen/exception/fory_codegen_exception.dart';
import 'package:fory/src/const/meta_string_const.dart';

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
