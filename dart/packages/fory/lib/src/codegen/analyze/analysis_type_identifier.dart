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

import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:fory/src/codegen/collection/type_3string_key.dart';

class AnalysisTypeIdentifier{

  static bool _objectTypeSet = false;
  static late final InterfaceType _objectType;
  static bool get objectTypeSet => _objectTypeSet;
  static set setObjectType(InterfaceType type){
    _objectTypeSet = true;
    _objectType = type;
  }
  static InterfaceType get objectType => _objectType;

  static int get dartCoreLibId => objectType.element.library.id;


  static final List<int?> _ids = [null,null,null,null,null,null,null,null];
  static final List<Type3StringKey> _keys = [
    Type3StringKey(
      'ForyClass',
      'package',
      'fory/src/annotation/fory_class.dart',
    ),
    Type3StringKey(
      'ForyKey',
      'package',
      'fory/src/annotation/fory_key.dart',
    ),
    Type3StringKey(
      'ForyCons',
      'package',
      'fory/src/annotation/fory_constructor.dart',
    ),
    Type3StringKey(
      'ForyEnum',
      'package',
      'fory/src/annotation/fory_enum.dart',
    ),
    Type3StringKey(
      'Uint8Type',
      'package',
      'fory/src/annotation/uint_types.dart',
    ),
    Type3StringKey(
      'Uint16Type',
      'package',
      'fory/src/annotation/uint_types.dart',
    ),
    Type3StringKey(
      'Uint32Type',
      'package',
      'fory/src/annotation/uint_types.dart',
    ),
    Type3StringKey(
      'Uint64Type',
      'package',
      'fory/src/annotation/uint_types.dart',
    ),
  ];

  static bool _check(ClassElement element, int index){
    if (_ids[index] != null){
      return element.id == _ids[index];
    }
    Uri uri = element.librarySource.uri;
    Type3StringKey key = Type3StringKey(
      element.name,
      uri.scheme,
      uri.path,
    );
    if (key.hashCode != _keys[index].hashCode || key != _keys[index]){
      return false;
    }
    _ids[index] = element.id;
    return true;
  }

  static bool isForyClass(ClassElement element){
    return _check(element, 0);
  }

  static bool isForyKey(ClassElement element){
    return _check(element, 1);
  }

  static bool isForyCons(ClassElement element){
    return _check(element, 2);
  }

  static bool isForyEnum(ClassElement element){
    return _check(element, 3);
  }

  static void giveForyEnumId(int id){
    _ids[3] = id;
  }

  static void giveForyClassId(int id){
    _ids[0] = id;
  }

  static bool isUint8Type(ClassElement element){
    return _check(element, 4);
  }

  static bool isUint16Type(ClassElement element){
    return _check(element, 5);
  }

  static bool isUint32Type(ClassElement element){
    return _check(element, 6);
  }

  static bool isUint64Type(ClassElement element){
    return _check(element, 7);
  }

}