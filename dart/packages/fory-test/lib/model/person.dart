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

part 'person.fory.dart';

enum Color {
  red,
  blue,
}

@ForyStruct()
class Person {
  Person();

  String name = '';
  Int32 age = Int32(0);
  Color favoriteColor = Color.red;
  List<String?> tags = <String?>[];
  Map<String, Int32> scores = <String, Int32>{};
}

@ForyStruct()
class RefNode {
  RefNode();

  String name = '';

  @ForyField(ref: true)
  RefNode? self;
}

@ForyStruct()
class EvolvingPayload {
  EvolvingPayload();

  String value = '';
}

@ForyStruct(evolving: false)
class FixedPayload {
  FixedPayload();

  String value = '';
}

@ForyStruct()
class PrivatePayload {
  PrivatePayload([this._secret = '']);

  String _secret;

  String get secret => _secret;

  void updateSecret(String value) {
    _secret = value;
  }
}

@ForyStruct()
class PrivateImmutablePayload {
  PrivateImmutablePayload(this._secret);

  final String _secret;

  String get secret => _secret;
}
