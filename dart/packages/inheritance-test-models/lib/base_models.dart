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

library;

import 'package:fory/fory.dart';

part 'base_models.fory.dart';

class _PrivateGenericStorage<T> {
  _PrivateGenericStorage(T storedValue) : _ownerValue = storedValue;

  @ForyField(id: 1)
  final T _ownerValue;

  @ForyField(id: 2)
  String _sharedName = 'base-default';

  @ForyField(id: 4)
  String publicLabel = '';

  T get ownerValue => _ownerValue;

  String get basePrivateName => _sharedName;

  void setBasePrivateName(String value) {
    _sharedName = value;
  }
}

@ForyStruct(exposePrivateFields: true)
abstract class PublicGenericBoundary<T> extends _PrivateGenericStorage<T> {
  // Keep explicit forwarding to exercise constructor identity analysis.
  // ignore: use_super_parameters
  PublicGenericBoundary(T value) : super(value);
}

@ForyStruct(exposePrivateFields: true)
mixin PublicPrivateMixin {
  @ForyField(id: 3, type: Int32Type())
  int _mixinCount = 0;

  int get mixinCount => _mixinCount;

  void setMixinCount(int value) {
    _mixinCount = value;
  }
}

@ForyStruct(exposePrivateFields: true)
abstract class XlangInheritanceBoundary {
  @ForyField(id: 2, type: Int32Type())
  int _parentInt = 0;

  @ForyField(id: 6)
  bool _parentFlag = false;

  @ForyField(id: 10)
  String _parentText = '';

  int get parentInt => _parentInt;

  bool get parentFlag => _parentFlag;

  String get parentText => _parentText;

  void setParentValues({
    required int parentInt,
    required bool parentFlag,
    required String parentText,
  }) {
    _parentInt = parentInt;
    _parentFlag = parentFlag;
    _parentText = parentText;
  }
}

class UnexposedPrivateBase {
  String _basePrivate = 'base-private-default';

  @ForyField(id: 30)
  String basePublic = '';

  String get basePrivate => _basePrivate;

  void setBasePrivate(String value) {
    _basePrivate = value;
  }
}

mixin UnexposedPrivateMixin {
  String _mixinPrivate = 'mixin-private-default';

  String get mixinPrivate => _mixinPrivate;

  void setMixinPrivate(String value) {
    _mixinPrivate = value;
  }
}
