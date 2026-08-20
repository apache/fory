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

import 'package:fory/fory.dart';
import 'package:fory_test/model/inheritance_models.dart';

void main() {
  final fory = Fory();
  InheritanceModelsForyModule.register(fory, CrossLibraryChild, id: 401);
  final input =
      CrossLibraryChild('base-final', 37, true)
        ..setBasePrivateName('base-private')
        ..setMiddlePrivateName('middle-private')
        ..setMixinCount(9)
        ..publicLabel = 'base-public'
        ..publicMiddleCount = 12
        ..childMutable = 'child';
  final result = fory.deserialize<CrossLibraryChild>(fory.serialize(input));
  if (result.ownerValue != 'base-final' ||
      result.middleValue != 37 ||
      !result.childFinal ||
      result.basePrivateName != 'base-private' ||
      result.middlePrivateName != 'middle-private' ||
      result.mixinCount != 9 ||
      result.publicLabel != 'base-public' ||
      result.publicMiddleCount != 12 ||
      result.childMutable != 'child') {
    throw StateError('Inherited structural serializer round trip failed.');
  }
}
