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
import 'package:inheritance_test_models/base_models.dart';

part 'middle_models.fory.dart';

@ForyStruct(exposePrivateFields: true)
abstract class PublicMiddleBoundary extends PublicGenericBoundary<String>
    with PublicPrivateMixin {
  // Keep explicit forwarding to exercise constructor identity analysis.
  // ignore: use_super_parameters
  PublicMiddleBoundary(String baseValue, int middleValue)
    : _middleValue = middleValue,
      super(baseValue);

  @ForyField(id: 11, type: Int32Type())
  final int _middleValue;

  @ForyField(id: 12)
  String _sharedName = 'middle-default';

  @ForyField(id: 14, type: Int32Type())
  int publicMiddleCount = 0;

  int get middleValue => _middleValue;

  String get middlePrivateName => _sharedName;

  void setMiddlePrivateName(String value) {
    _sharedName = value;
  }
}

class UnexposedPrivateMiddle extends UnexposedPrivateBase
    with UnexposedPrivateMixin {
  String _middlePrivate = 'middle-private-default';

  @ForyField(id: 20)
  String middlePublic = '';

  String get middlePrivate => _middlePrivate;

  void setMiddlePrivate(String value) {
    _middlePrivate = value;
  }
}
