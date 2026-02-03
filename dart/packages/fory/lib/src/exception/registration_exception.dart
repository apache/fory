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

import 'package:fory/src/exception/fory_exception.dart';

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
  final String _newTag;

  DuplicatedTypeRegistrationException(this._forType, this._newTag);

  @override
  void giveExceptionMessage(StringBuffer buf) {
    super.giveExceptionMessage(buf);
    buf.write('Duplicate registration for type: ');
    buf.writeln(_forType);
    buf.write('\nBut you try to register another tag: ');
    buf.writeln(_newTag);
  }
}
