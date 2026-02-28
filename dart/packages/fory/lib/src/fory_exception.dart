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

import 'package:fory/src/const/types.dart';

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
