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

import 'dart:collection';
import 'package:fory/src/codegen/entity/constructor_params.dart';
import 'package:fory/src/codegen/entity/fields_cache_unit.dart';
import 'package:fory/src/codegen/meta/impl/type_immutable.dart';
import 'package:fory/src/codegen/meta/library_import_pack.dart';

class AnalysisCache {
  static final Map<int, ConstructorParams> _clsIdToUnnamedCons = HashMap();
  static final Map<int, FieldsCacheUnit> _clsIdToFields = HashMap();

  // Each file has its own imports, treated here as a unit (lib) per file
  static final Map<int, LibraryImportPack> _libToImport = HashMap();

  static final Map<int, TypeImmutable> _typeId2TypeImmutable = HashMap();

  /*--------------------------------------------------------------------*/
  static ConstructorParams? getUnnamedConstructorParams(int id) {
    return _clsIdToUnnamedCons[id];
  }

  // the modification of the return value is not allowed
  static void putUnnamedConstructorParams(int id, ConstructorParams params) {
    _clsIdToUnnamedCons[id] = params;
  }

  /*--------------------------------------------------------------------*/
  // the modification of the return value is not allowed
  static FieldsCacheUnit? getFields(int id) {
    return _clsIdToFields[id];
  }

  static void putFields(int id, FieldsCacheUnit unit) {
    _clsIdToFields[id] = unit;
  }

  static TypeImmutable? getTypeImmutable(int id) {
    return _typeId2TypeImmutable[id];
  }

  static void putTypeImmutable(int id, TypeImmutable typeImmutable) {
    _typeId2TypeImmutable[id] = typeImmutable;
  }

  /*-----------------lib imports---------------------------------------------------*/
  // the modification of the return value is not allowed
  static LibraryImportPack? getLibraryImportPack(int id) {
    return _libToImport[id];
  }

  static void putLibraryImportPack(int id, LibraryImportPack libImport) {
    _libToImport[id] = libImport;
  }
}
