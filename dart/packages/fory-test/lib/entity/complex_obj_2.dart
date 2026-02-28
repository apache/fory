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
import 'package:fory_test/extensions/map_ext.dart';

part '../generated/complex_obj_2.g.dart';

@ForyClass(promiseAcyclic: true)
class ComplexObject2 {
  final Object f1;
  final Map<Int8, Int32> f2;

  const ComplexObject2(this.f1, this.f2);

  @override
  int get hashCode => Object.hash(f1, f2);

  @override
  bool operator ==(Object other) {
    return identical(this, other) ||
        (other is ComplexObject2 &&
            runtimeType == other.runtimeType &&
            f1 == other.f1 &&
            f2.equals(other.f2));
  }
}
