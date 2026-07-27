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

import 'dart:typed_data';

import 'package:external_type_test_models/models.dart' as third_party;
import 'package:fory/fory.dart';

part 'external_serializers.fory.dart';

@ForyStruct(target: third_party.User)
abstract final class UserSerializer {
  @ForyField(id: 1)
  late final String name;

  @ForyField(id: 2, type: Int32Type())
  late final int age;
}

@ForyStruct(target: third_party.Point)
abstract final class PointSerializer {
  @ForyField(id: 1, type: Int32Type())
  late final int x;

  @ForyField(id: 2, type: Int32Type())
  late final int y;
}

@ForyStruct(target: third_party.Money, constructor: 'fromParts')
abstract final class MoneySerializer {
  @ForyField(id: 1)
  late final String currency;

  @ForyField(id: 2, type: Int64Type())
  late final int units;
}

@ForyStruct(target: third_party.MutableProfile)
abstract final class MutableProfileSerializer {
  @ForyField(id: 1)
  late final String name;

  @ForyField(id: 2, type: Int32Type())
  late final int score;
}

@ForyStruct(target: third_party.NamedMutableProfile, constructor: 'empty')
abstract final class NamedMutableProfileSerializer {
  @ForyField(id: 1)
  late final String name;

  @ForyField(id: 2, type: Int32Type())
  late final int score;
}

@ForyStruct(target: third_party.DerivedValue)
abstract final class DerivedValueSerializer {
  @ForyField(id: 1)
  late final String value;

  @ForyField(id: 2, type: Int32Type())
  late final int count;
}

@ForyStruct(target: third_party.Box<String>)
abstract final class StringBoxSerializer {
  @ForyField(id: 1)
  late final String value;
}

@ForyStruct(target: third_party.Node, constructor: 'empty')
abstract final class NodeSerializer {
  @ForyField(id: 1)
  late final String label;

  @ForyField(id: 2, ref: true)
  late final third_party.Node? next;
}

@ForyStruct(target: third_party.LinkA)
abstract final class LinkASerializer {
  @ForyField(id: 1)
  late final String label;

  @ForyField(id: 2, ref: true)
  late final third_party.LinkB? link;
}

@ForyStruct(target: third_party.LinkB)
abstract final class LinkBSerializer {
  @ForyField(id: 1)
  late final String label;

  @ForyField(id: 2, ref: true)
  late final third_party.LinkA? link;
}

@ForyStruct(target: third_party.Envelope)
abstract final class EnvelopeSerializer {
  @ForyField(id: 1)
  late final third_party.User? user;

  @ListField(id: 2, ref: true, element: DeclaredType(ref: true))
  late final List<third_party.User> users;

  @SetField(id: 3, element: DeclaredType())
  late final Set<third_party.User> userSet;

  @MapField(id: 4, value: DeclaredType())
  late final Map<String, third_party.User> usersByName;

  @MapField(id: 5, key: DeclaredType())
  late final Map<third_party.User, String> namesByUser;

  @ForyField(
    id: 6,
    type: ListType(element: MapType(value: ListType(element: DeclaredType()))),
  )
  late final List<Map<String, List<third_party.User>>> nested;

  @ForyField(id: 7, dynamic: true)
  late final Object? value;

  @ListField(id: 8, dynamic: true, element: DeclaredType(dynamic: true))
  late final List<Object?> values;
}

@ForyStruct(target: third_party.ScalarValues, evolving: false)
abstract final class ScalarValuesSerializer {
  @ForyField(id: 1, type: BoolType())
  late final bool flag;

  @ForyField(id: 2, type: Int8Type())
  late final int int8;

  @ForyField(id: 3, type: Int16Type())
  late final int int16;

  @ForyField(id: 4, type: Int32Type())
  late final int int32;

  @ForyField(id: 5, type: Int64Type())
  late final int int64;

  @ForyField(id: 6, type: Uint8Type())
  late final int uint8;

  @ForyField(id: 7, type: Uint16Type())
  late final int uint16;

  @ForyField(id: 8, type: Uint32Type())
  late final int uint32;

  @ForyField(id: 9, type: Uint64Type())
  late final int uint64;

  @ForyField(id: 10, type: Float16Type())
  late final double float16;

  @ForyField(id: 11, type: Bfloat16Type())
  late final double bfloat16;

  @ForyField(id: 12, type: Float32Type())
  late final double float32;

  @ForyField(id: 13, type: Float64Type())
  late final double float64;

  @ForyField(id: 14, type: StringType())
  late final String text;

  @ForyField(id: 15, type: TimestampType())
  late final DateTime timestamp;

  @ForyField(id: 16, type: DurationType())
  late final Duration duration;

  @ForyField(id: 17, type: BinaryType())
  late final Uint8List binary;
}

@ForyStruct(target: third_party.ContactVersion1)
abstract final class ContactVersion1Serializer {
  @ForyField(id: 1)
  late final String firstName;

  @ForyField(id: 2)
  late final String? nickname;
}

@ForyStruct(target: third_party.ContactVersion2)
abstract final class ContactVersion2Serializer {
  @ForyField(id: 3)
  late final String? email;

  @ForyField(id: 1)
  late final String fullName;

  @ForyField(id: 2)
  late final String? nickname;
}

@ForyStruct(target: third_party.EquivalentUser)
abstract final class EquivalentUserSerializer {
  @ForyField(id: 1)
  late final String name;

  @ForyField(id: 2, type: Int32Type())
  late final int age;
}

@ForyStruct()
final class OrdinaryEquivalentUser {
  const OrdinaryEquivalentUser({required this.name, required this.age});

  @ForyField(id: 1)
  final String name;

  @ForyField(id: 2, type: Int32Type())
  final int age;
}

@ForyStruct()
final class UserHolder {
  UserHolder();

  @ForyField(id: 1)
  third_party.User? user;

  @ListField(id: 2, ref: true, element: DeclaredType(ref: true))
  List<third_party.User> users = <third_party.User>[];

  @SetField(id: 3, element: DeclaredType())
  Set<third_party.User> userSet = <third_party.User>{};

  @MapField(id: 4, value: DeclaredType())
  Map<String, third_party.User> usersByName = <String, third_party.User>{};

  @MapField(id: 5, key: DeclaredType())
  Map<third_party.User, String> namesByUser = <third_party.User, String>{};

  @ForyField(
    id: 6,
    type: ListType(element: MapType(value: ListType(element: DeclaredType()))),
  )
  List<Map<String, List<third_party.User>>> nested =
      <Map<String, List<third_party.User>>>[];

  @ForyField(id: 7, dynamic: true)
  Object? value;

  @ListField(id: 8, dynamic: true, element: DeclaredType(dynamic: true))
  List<Object?> values = <Object?>[];
}

@ForyStruct(evolving: false)
final class TargetDirectHolder {
  const TargetDirectHolder(this.value);

  @ForyField(id: 1)
  final third_party.EquivalentUser value;
}

@ForyStruct(evolving: false)
final class OrdinaryDirectHolder {
  const OrdinaryDirectHolder(this.value);

  @ForyField(id: 1)
  final OrdinaryEquivalentUser value;
}

@ForyStruct(evolving: false)
final class TargetListHolder {
  const TargetListHolder(this.values);

  @ListField(id: 1, element: DeclaredType())
  final List<third_party.EquivalentUser> values;
}

@ForyStruct(evolving: false)
final class OrdinaryListHolder {
  const OrdinaryListHolder(this.values);

  @ListField(id: 1, element: DeclaredType())
  final List<OrdinaryEquivalentUser> values;
}

@ForyStruct(evolving: false)
final class TargetMapHolder {
  const TargetMapHolder(this.values);

  @MapField(id: 1, value: DeclaredType())
  final Map<String, third_party.EquivalentUser> values;
}

@ForyStruct(evolving: false)
final class OrdinaryMapHolder {
  const OrdinaryMapHolder(this.values);

  @MapField(id: 1, value: DeclaredType())
  final Map<String, OrdinaryEquivalentUser> values;
}
