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

class ConstructorParam {
  final String name;
  final bool optional;

  /// This means that this constructor parameter corresponds to the field index of the (sorted fields list)
  late final int _fieldIndex; // -1 means not needed

  ConstructorParam._(this.name, this.optional) {
    assert(name.isNotEmpty);
  }

  ConstructorParam.withName(this.name, this.optional) {
    assert(name.isNotEmpty);
  }

  void setFieldIndex(int index) {
    assert(index >= 0);
    _fieldIndex = index;
  }

  void setNotInclude() {
    assert(optional);
    _fieldIndex = -1;
  }

  int get fieldIndex => _fieldIndex;

  ConstructorParam copyWithOptional(bool optional) =>
      ConstructorParam._(name, optional);
}
